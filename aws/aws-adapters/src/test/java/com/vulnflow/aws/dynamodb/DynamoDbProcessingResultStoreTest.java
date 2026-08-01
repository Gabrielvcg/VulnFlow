package com.vulnflow.aws.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.vulnflow.contract.IngestionEventV1;
import com.vulnflow.processing.FindingSeverity;
import com.vulnflow.processing.NormalizedFinding;
import com.vulnflow.processing.ProcessedVulnerabilityReport;
import com.vulnflow.processing.port.ProcessingFailure;
import com.vulnflow.processing.port.ProcessingResultStatus;
import com.vulnflow.processing.port.ProcessingStoreConflictException;
import com.vulnflow.processing.port.ProcessingStoreOutcome;
import com.vulnflow.processing.port.TransientProcessingStoreException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

@ExtendWith(MockitoExtension.class)
class DynamoDbProcessingResultStoreTest {
    @Mock
    private DynamoDbClient client;

    private DynamoDbProcessingResultStore store;
    private IngestionEventV1 event;

    @BeforeEach
    void setUp() {
        store = new DynamoDbProcessingResultStore(client, "vulnflow-results", 100_000);
        event = event(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64));
        lenient().when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        lenient().when(client.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().unprocessedItems(Map.of()).build());
    }

    @Test
    void storesAResultWithAnAtomicCommitMarker() {
        ProcessingStoreOutcome outcome = store.store(event, report(event, List.of(finding(0))));

        assertThat(outcome).isEqualTo(ProcessingStoreOutcome.STORED);
        verify(client, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
        verify(client).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void treatsTheExactFinalizedEventAsADuplicate() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(eventItem(event, "COMPLETED", 1)).build());

        ProcessingStoreOutcome outcome = store.store(event, report(event, List.of(finding(0))));

        assertThat(outcome).isEqualTo(ProcessingStoreOutcome.DUPLICATE);
        verify(client, times(0)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void rejectsAnEventIdReusedForDifferentContent() {
        IngestionEventV1 other = event(event.eventId(), event.scanId(), event.assetId(), "b".repeat(64));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(eventItem(event, "COMPLETED", 1)).build());

        assertThatThrownBy(() -> store.store(other, report(other, List.of(finding(0)))))
                .isInstanceOf(ProcessingStoreConflictException.class);
    }

    @Test
    void mapsAServiceFailureDuringTheInitialTransactionAsTransient() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(DynamoDbException.builder().statusCode(503).message("unavailable").build());

        assertThatThrownBy(() -> store.store(event, report(event, List.of())))
                .isInstanceOf(TransientProcessingStoreException.class);
    }

    @Test
    void batchesReportsLargerThanTheDynamoDbTransactionLimit() {
        List<NormalizedFinding> findings = IntStream.range(0, 61).mapToObj(this::finding).toList();

        assertThat(store.store(event, report(event, findings))).isEqualTo(ProcessingStoreOutcome.STORED);
        verify(client, times(3)).batchWriteItem(any(BatchWriteItemRequest.class));
        verify(client, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void storesAPermanentFailureAtomically() {
        ProcessingStoreOutcome outcome = store.storeFailure(
                event,
                new ProcessingFailure("INVALID_REPORT", "The report is invalid", Instant.now()));

        assertThat(outcome).isEqualTo(ProcessingStoreOutcome.STORED);
        verify(client).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void mapsAReadServiceFailureAsTransient() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenThrow(DynamoDbException.builder().statusCode(500).message("unavailable").build());

        assertThatThrownBy(() -> store.isFinalized(event))
                .isInstanceOf(TransientProcessingStoreException.class);
    }

    @Test
    void returnsAnOpaqueCursorForPaginatedFindings() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(scanItem(event, "COMPLETED", 2)).build());
        Map<String, AttributeValue> finding = findingItem(event, "FINDING#00000000");
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(finding)
                .lastEvaluatedKey(Map.of(
                        "pk", value("SCAN#" + event.scanId()),
                        "sk", value("FINDING#00000000")))
                .build());

        var page = store.findFindings(event.scanId(), null, 1);

        assertThat(page.findings()).singleElement()
                .extracting(result -> result.vulnerabilityId())
                .isEqualTo("CVE-2026-0001");
        assertThat(page.nextCursor()).isNotBlank();
    }

    @Test
    void readsACompletedSummaryWithSeverityCounts() {
        Map<String, AttributeValue> item = scanItem(event, "COMPLETED", 3);
        item.put("scannerVersion", value("0.60.0"));
        item.put("severity_critical", number(2));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        var summary = store.findScan(event.scanId()).orElseThrow();

        assertThat(summary.status()).isEqualTo(ProcessingResultStatus.COMPLETED);
        assertThat(summary.findingCount()).isEqualTo(3);
        assertThat(summary.severitySummary()).containsEntry("CRITICAL", 2);
    }

    private IngestionEventV1 event(UUID eventId, UUID scanId, UUID assetId, String hash) {
        return new IngestionEventV1("1", eventId, scanId, assetId, "reports/report.json", hash,
                "TRIVY", Instant.parse("2026-08-01T10:00:00Z"), UUID.randomUUID());
    }

    private ProcessedVulnerabilityReport report(IngestionEventV1 value, List<NormalizedFinding> findings) {
        return new ProcessedVulnerabilityReport(value.scanId(), value.assetId(), "0.60.0", findings);
    }

    private NormalizedFinding finding(int index) {
        return new NormalizedFinding("CVE-2026-" + index, "openssl", "1", "2",
                FindingSeverity.CRITICAL, "title", "description", false, 90);
    }

    private Map<String, AttributeValue> eventItem(IngestionEventV1 value, String status, int count) {
        return identityItem(value, "EVENT#" + value.eventId(), status, count);
    }

    private Map<String, AttributeValue> scanItem(IngestionEventV1 value, String status, int count) {
        return identityItem(value, "SCAN#" + value.scanId(), status, count);
    }

    private Map<String, AttributeValue> identityItem(
            IngestionEventV1 value, String pk, String status, int count) {
        return new java.util.HashMap<>(Map.ofEntries(
                Map.entry("pk", value(pk)),
                Map.entry("sk", value("META")),
                Map.entry("eventId", value(value.eventId().toString())),
                Map.entry("scanId", value(value.scanId().toString())),
                Map.entry("assetId", value(value.assetId().toString())),
                Map.entry("correlationId", value(value.correlationId().toString())),
                Map.entry("contentHash", value(value.contentHash())),
                Map.entry("scanner", value("TRIVY")),
                Map.entry("status", value(status)),
                Map.entry("findingCount", number(count)),
                Map.entry("receivedAt", value(value.createdAt().toString())),
                Map.entry("completedAt", value(value.createdAt().plusSeconds(1).toString()))));
    }

    private Map<String, AttributeValue> findingItem(IngestionEventV1 value, String sk) {
        return Map.ofEntries(
                Map.entry("pk", value("SCAN#" + value.scanId())),
                Map.entry("sk", value(sk)),
                Map.entry("vulnerabilityId", value("CVE-2026-0001")),
                Map.entry("packageName", value("openssl")),
                Map.entry("severity", value("CRITICAL")),
                Map.entry("knownExploited", AttributeValue.builder().bool(false).build()),
                Map.entry("riskScore", number(90)));
    }

    private AttributeValue value(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private AttributeValue number(int value) {
        return AttributeValue.builder().n(Integer.toString(value)).build();
    }
}
