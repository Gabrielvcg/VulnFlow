package com.vulnflow.aws.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnflow.aws.dynamodb.DynamoDbProcessingResultStore;
import com.vulnflow.aws.lambda.SqsVulnerabilityReportHandler;
import com.vulnflow.contract.IngestionEventJsonCodec;
import com.vulnflow.contract.IngestionEventV1;
import com.vulnflow.processing.DefaultFindingRiskCalculator;
import com.vulnflow.processing.PayloadIntegrityVerifier;
import com.vulnflow.processing.TrivyVulnerabilityReportParser;
import com.vulnflow.processing.VulnerabilityReportProcessingRequest;
import com.vulnflow.processing.VulnerabilityReportProcessor;
import com.vulnflow.processing.port.ProcessingResultStatus;
import com.vulnflow.processing.port.ProcessingStoreConflictException;
import com.vulnflow.processing.port.ProcessingStoreOutcome;
import com.vulnflow.processing.port.ReportStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

class AwsFindingLimitFlowTest {
    private final Map<String, Map<String, AttributeValue>> rows = new HashMap<>();
    private final List<TransactWriteItemsRequest> transactions = new ArrayList<>();
    private final List<BatchWriteItemRequest> findingWrites = new ArrayList<>();
    private final Map<String, byte[]> payloads = new HashMap<>();
    private final List<String> payloadLoads = new ArrayList<>();
    private Consumer<TransactWriteItemsRequest> beforeTransaction = request -> {};
    private final DynamoDbClient client = mock(DynamoDbClient.class);
    private final ReportStorage storage = mock(ReportStorage.class);
    private final DynamoDbProcessingResultStore store = new DynamoDbProcessingResultStore(client, "demo-results", 1);
    private final VulnerabilityReportProcessor processor = new VulnerabilityReportProcessor(new PayloadIntegrityVerifier(),
            new TrivyVulnerabilityReportParser(new ObjectMapper(), 8000), new DefaultFindingRiskCalculator());
    private final IngestionEventJsonCodec codec = new IngestionEventJsonCodec();

    AwsFindingLimitFlowTest() {
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(invocation -> {
            GetItemRequest request = invocation.getArgument(0);
            assertThat(request.consistentRead()).isTrue();
            return GetItemResponse.builder().item(rows.getOrDefault(key(request.key()), Map.of())).build();
        });
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenAnswer(invocation -> {
            TransactWriteItemsRequest request = invocation.getArgument(0);
            transactions.add(request);
            beforeTransaction.accept(request);
            // This SDK double enforces only the conditional-put boundary used by these failure tests.
            for (var write : request.transactItems()) {
                if (write.put() != null && rows.containsKey(key(write.put().item()))) {
                    throw TransactionCanceledException.builder().message("fixture conditional conflict").build();
                }
            }
            for (var write : request.transactItems()) {
                if (write.put() != null) {
                    assertThat(write.put().conditionExpression()).contains("attribute_not_exists");
                    rows.put(key(write.put().item()), new HashMap<>(write.put().item()));
                } else {
                    // Only the existing successful finalization update is modeled, not general DynamoDB expressions.
                    var item = rows.get(key(write.update().key()));
                    item.put("status", text("COMPLETED"));
                    item.put("completedAt", text(Instant.EPOCH.toString()));
                }
            }
            return TransactWriteItemsResponse.builder().build();
        });
        when(client.batchWriteItem(any(BatchWriteItemRequest.class))).thenAnswer(invocation -> {
            findingWrites.add(invocation.getArgument(0));
            return BatchWriteItemResponse.builder().build();
        });
        when(storage.load(any())).thenAnswer(invocation -> {
            String payloadKey = invocation.getArgument(0);
            payloadLoads.add(payloadKey);
            return payloads.get(payloadKey);
        });
    }

    @Test
    void firstOversizedReportIsAcknowledgedOnlyAfterBothFailedMarkersAreWritten() throws Exception {
        IngestionEventV1 event = event(2);

        assertThat(deliver(event).getBatchItemFailures()).isEmpty();

        assertThat(transactions).hasSize(1);
        assertThat(transactions.get(0).transactItems()).hasSize(2);
        assertThat(rows).hasSize(2);
        assertThat(rows.values()).allSatisfy(item -> {
            assertThat(item.get("status").s()).isEqualTo("FAILED");
            assertThat(item.get("errorCode").s()).isEqualTo("INVALID_REPORT");
        });
        assertThat(store.findScan(event.scanId()).orElseThrow().status()).isEqualTo(ProcessingResultStatus.FAILED);
        assertThat(findingWrites).isEmpty();
    }

    @Test
    void reportExactlyAtTheLimitStillCompletes() throws Exception {
        IngestionEventV1 event = event(1);
        assertThat(deliver(event).getBatchItemFailures()).isEmpty();
        assertThat(store.findScan(event.scanId()).orElseThrow().status()).isEqualTo(ProcessingResultStatus.COMPLETED);
        assertThat(transactions).hasSize(2);
        assertThat(findingWrites).hasSize(1);
    }

    @Test
    void transientFailureSavingTheRejectionReturnsOnlyThatBatchItemForRetry() throws Exception {
        IngestionEventV1 oversized = event(2);
        IngestionEventV1 valid = event(1);
        beforeTransaction = request -> {
            if (request.transactItems().get(0).put() != null
                    && request.transactItems().get(0).put().item().get("eventId").s().equals(oversized.eventId().toString())) {
                throw DynamoDbException.builder().statusCode(503).message("fixture store unavailable").build();
            }
        };

        assertThat(deliver(oversized, valid).getBatchItemFailures()).extracting(failure -> failure.getItemIdentifier())
                .containsExactly(oversized.eventId().toString());
        assertThat(store.findScan(oversized.scanId())).isEmpty();
        assertThat(store.findScan(valid.scanId()).orElseThrow().status()).isEqualTo(ProcessingResultStatus.COMPLETED);
    }

    @Test
    void repeatedOversizedDeliveryKeepsItsOriginalFailedResultWithoutLoadingPayloadAgain() throws Exception {
        IngestionEventV1 event = event(2);
        deliver(event);
        Map<String, Map<String, AttributeValue>> original = snapshot();

        assertThat(deliver(event).getBatchItemFailures()).isEmpty();

        assertThat(payloadLoads).hasSize(1);
        assertThat(transactions).hasSize(1);
        assertThat(rows).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED"})
    void finalizedIdentityIsNotOverwrittenEvenIfCurrentLimitIsLower(String state) throws Exception {
        IngestionEventV1 event = event(2);
        seed(event, state, state.equals("FAILED") ? 0 : 2);
        Map<String, Map<String, AttributeValue>> original = snapshot();
        assertThat(deliver(event).getBatchItemFailures()).isEmpty();
        assertThat(payloadLoads).isEmpty();
        assertThat(transactions).isEmpty();
        assertThat(rows).isEqualTo(original);
        if (state.equals("COMPLETED")) {
            // A matching completed duplicate resolves before applying a lower new-work limit.
            assertThat(store.store(event, processor.process(new VulnerabilityReportProcessingRequest(event.scanId(),
                    event.assetId(), event.contentHash(), payloads.get(event.payloadKey()))))).isEqualTo(ProcessingStoreOutcome.DUPLICATE);
        }
    }

    @Test
    void trueEventIdentityConflictNeverWritesFailedMarkers() throws Exception {
        IngestionEventV1 event = event(2);
        seed(event, "WRITING", 2);
        rows.values().forEach(item -> item.put("contentHash", text("b".repeat(64))));
        Map<String, Map<String, AttributeValue>> original = snapshot();

        assertThat(deliver(event).getBatchItemFailures()).isEmpty();
        assertThat(storeConflict(event)).isInstanceOf(ProcessingStoreConflictException.class);
        assertThat(transactions).isEmpty();
        assertThat(rows).isEqualTo(original);
    }

    @Test
    void existingWritingResultAboveANewLimitIsPreservedAndRequestsRetry() throws Exception {
        IngestionEventV1 event = event(2);
        seed(event, "WRITING", 2);
        Map<String, Map<String, AttributeValue>> original = snapshot();

        assertThat(deliver(event).getBatchItemFailures()).extracting(failure -> failure.getItemIdentifier())
                .containsExactly(event.eventId().toString());

        assertThat(rows).isEqualTo(original);
        assertThat(transactions).isEmpty();
        assertThat(findingWrites).isEmpty();
    }

    @Test
    void writingResultThatAppearsDuringFailurePersistenceIsNotOverwrittenOrAcknowledged() throws Exception {
        IngestionEventV1 event = event(2);
        beforeTransaction = request -> seed(event, "WRITING", 2);

        assertThat(deliver(event).getBatchItemFailures()).extracting(failure -> failure.getItemIdentifier())
                .containsExactly(event.eventId().toString());
        assertThat(rows.values()).allSatisfy(item -> assertThat(item.get("status").s()).isEqualTo("WRITING"));
    }

    @Test
    void mixedSuccessfulBatchPersistsBothCompletionAndRejectionBeforeAcknowledgement() throws Exception {
        IngestionEventV1 oversized = event(2);
        IngestionEventV1 valid = event(1);

        assertThat(deliver(oversized, valid).getBatchItemFailures()).isEmpty();

        assertThat(store.findScan(oversized.scanId()).orElseThrow().status()).isEqualTo(ProcessingResultStatus.FAILED);
        assertThat(store.findScan(valid.scanId()).orElseThrow().status()).isEqualTo(ProcessingResultStatus.COMPLETED);
        assertThat(transactions).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED", "CONFLICT"})
    void aFinalizedOrConflictingResultWinningTheFailureWriteRaceIsNotOverwritten(String state) throws Exception {
        IngestionEventV1 event = event(2);
        beforeTransaction = request -> {
            seed(event, state.equals("CONFLICT") ? "WRITING" : state, state.equals("FAILED") ? 0 : 2);
            if (state.equals("CONFLICT")) rows.values().forEach(item -> item.put("contentHash", text("b".repeat(64))));
        };

        assertThat(deliver(event).getBatchItemFailures()).isEmpty();

        assertThat(transactions).hasSize(1);
        assertThat(rows.values()).allSatisfy(item -> {
            assertThat(item.get("status").s()).isEqualTo(state.equals("CONFLICT") ? "WRITING" : state);
            assertThat(item.get("contentHash").s()).isEqualTo(state.equals("CONFLICT") ? "b".repeat(64) : event.contentHash());
            assertThat(item).doesNotContainKey("errorCode");
        });
    }

    @Test
    void changedNormalizedCountOnWritingRemainsAConflictRatherThanALimitFailure() throws Exception {
        IngestionEventV1 event = event(2);
        seed(event, "WRITING", 1);
        var original = snapshot();

        assertThat(deliver(event).getBatchItemFailures()).isEmpty();
        assertThat(storeConflict(event)).isInstanceOf(ProcessingStoreConflictException.class);
        assertThat(rows).isEqualTo(original);
        assertThat(transactions).isEmpty();
    }

    private RuntimeException storeConflict(IngestionEventV1 event) {
        try {
            store.store(event, processor.process(new VulnerabilityReportProcessingRequest(event.scanId(), event.assetId(),
                    event.contentHash(), payloads.get(event.payloadKey()))));
            throw new AssertionError("Expected identity conflict");
        } catch (RuntimeException failure) { return failure; }
    }

    private com.amazonaws.services.lambda.runtime.events.SQSBatchResponse deliver(IngestionEventV1... events) {
        SQSEvent batch = new SQSEvent();
        batch.setRecords(java.util.Arrays.stream(events).map(event -> {
            var message = new SQSEvent.SQSMessage();
            message.setMessageId(event.eventId().toString());
            message.setBody(codec.serialize(event));
            return message;
        }).toList());
        return new SqsVulnerabilityReportHandler(codec, storage, processor, store).handleRequest(batch, null);
    }

    private IngestionEventV1 event(int count) throws Exception {
        String entries = IntStream.range(0, count).mapToObj(i -> "{\"VulnerabilityID\":\"CVE-DEMO-" + i
                + "\",\"PkgName\":\"demo\",\"Severity\":\"HIGH\"}").collect(java.util.stream.Collectors.joining(","));
        byte[] payload = ("{\"Results\":[{\"Vulnerabilities\":[" + entries + "]}]}").getBytes(StandardCharsets.UTF_8);
        String key = "demo/" + UUID.randomUUID() + ".json";
        payloads.put(key, payload);
        return new IngestionEventV1("1", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), key,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)), "TRIVY", Instant.EPOCH, UUID.randomUUID());
    }

    private void seed(IngestionEventV1 event, String status, int count) {
        for (String pk : List.of("EVENT#" + event.eventId(), "SCAN#" + event.scanId())) {
            Map<String, AttributeValue> item = new HashMap<>(Map.of("pk", text(pk), "sk", text("META"),
                    "eventId", text(event.eventId().toString()), "scanId", text(event.scanId().toString()),
                    "assetId", text(event.assetId().toString()), "contentHash", text(event.contentHash()),
                    "scanner", text("TRIVY"), "status", text(status), "findingCount", AttributeValue.builder().n(Integer.toString(count)).build()));
            rows.put(key(item), item);
        }
    }

    private Map<String, Map<String, AttributeValue>> snapshot() {
        Map<String, Map<String, AttributeValue>> copy = new HashMap<>();
        rows.forEach((key, value) -> copy.put(key, new HashMap<>(value)));
        return copy;
    }
    private static String key(Map<String, AttributeValue> item) { return item.get("pk").s() + "/" + item.get("sk").s(); }
    private static AttributeValue text(String value) { return AttributeValue.builder().s(value).build(); }
}
