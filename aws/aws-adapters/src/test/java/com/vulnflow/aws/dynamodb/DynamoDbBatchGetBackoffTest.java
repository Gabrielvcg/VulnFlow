package com.vulnflow.aws.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.vulnflow.processing.port.ProcessingStoreConflictException;
import com.vulnflow.processing.port.TransientProcessingStoreException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;

class DynamoDbBatchGetBackoffTest {
    private static final String TABLE = "vulnflow-results";
    private static final UUID ID = new UUID(0, 1);
    private final DynamoDbClient client = mock(DynamoDbClient.class);

    @Test
    void recoversKeysReleasedOnlyAfterAVirtualRecoveryWindow() {
        long[] now = {0};
        List<Long> waits = new ArrayList<>();
        List<Long> callTimes = new ArrayList<>();
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenAnswer(invocation -> {
            BatchGetItemRequest request = invocation.getArgument(0);
            callTimes.add(now[0]);
            return now[0] < 75
                    ? BatchGetItemResponse.builder().unprocessedKeys(request.requestItems()).build()
                    : BatchGetItemResponse.builder().responses(Map.of(TABLE, List.of(item(ID)))).build();
        });
        var store = new DynamoDbProcessingResultStore(client, TABLE, 100_000, delay -> {
            waits.add(delay);
            now[0] += delay;
        }, maximum -> maximum);

        assertThat(store.findScans(List.of(ID))).containsOnlyKeys(ID);
        assertThat(callTimes).containsExactly(0L, 50L, 150L);
        assertThat(waits).containsExactly(50L, 100L);
        System.out.println("Virtual recovery: call times=" + callTimes + ", waits=" + waits);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void boundsJitterAndWaitsOnlyBetweenThreeUnprocessedAttempts(int fraction) {
        List<Long> maxima = new ArrayList<>();
        List<Long> waits = new ArrayList<>();
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenAnswer(invocation ->
                BatchGetItemResponse.builder().unprocessedKeys(((BatchGetItemRequest) invocation.getArgument(0)).requestItems()).build());
        var store = new DynamoDbProcessingResultStore(client, TABLE, 100_000, waits::add, maximum -> {
            maxima.add(maximum);
            return maximum * fraction / 2;
        });

        assertThatThrownBy(() -> store.findScans(List.of(ID))).isInstanceOf(TransientProcessingStoreException.class);

        verify(client, times(3)).batchGetItem(any(BatchGetItemRequest.class));
        assertThat(maxima).containsExactly(50L, 100L);
        assertThat(waits).containsExactly(50L * fraction / 2, 100L * fraction / 2);
        assertThat(waits.stream().mapToLong(Long::longValue).sum()).isBetween(0L, 150L);
    }

    @Test
    void successfulFirstReadDoesNotWaitOrDrawJitter() {
        when(client.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(BatchGetItemResponse.builder().responses(Map.of(TABLE, List.of(item(ID)))).build());

        assertThat(noWaitingStore().findScans(List.of(ID))).containsOnlyKeys(ID);
        verify(client).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void emptyInputDoesNotCallTheClientOrWait() {
        assertThat(noWaitingStore().findScans(List.of())).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void accumulatesPartialResultsAndRetriesOnlyStronglyConsistentUnprocessedKeys() {
        UUID second = new UUID(0, 2), third = new UUID(0, 3);
        List<BatchGetItemRequest> requests = new ArrayList<>();
        List<Long> waits = new ArrayList<>();
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenAnswer(invocation -> {
            BatchGetItemRequest request = invocation.getArgument(0);
            requests.add(request);
            return switch (requests.size()) {
                case 1 -> BatchGetItemResponse.builder().responses(Map.of(TABLE, List.of(item(ID))))
                        .unprocessedKeys(pending(second, third)).build();
                case 2 -> BatchGetItemResponse.builder().responses(Map.of(TABLE, List.of(item(second))))
                        .unprocessedKeys(pending(third)).build();
                default -> BatchGetItemResponse.builder().responses(Map.of(TABLE, List.of(item(third)))).build();
            };
        });
        var store = new DynamoDbProcessingResultStore(client, TABLE, 100_000, waits::add, maximum -> maximum);

        assertThat(store.findScans(List.of(ID, second, third))).containsOnlyKeys(ID, second, third);

        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).requestItems()).isEqualTo(pending(ID, second, third));
        assertThat(requests.get(1).requestItems()).isEqualTo(pending(second, third));
        assertThat(requests.get(2).requestItems()).isEqualTo(pending(third));
        assertThat(waits).containsExactly(50L, 100L);
    }

    @Test
    void chunks101UniqueIdsDeduplicatesInputAndDoesNotRetryMissingItems() {
        List<UUID> ids = new ArrayList<>(IntStream.rangeClosed(1, 101).mapToObj(i -> new UUID(0, i)).toList());
        ids.add(ID);
        ids.add(new UUID(0, 101));
        List<BatchGetItemRequest> requests = new ArrayList<>();
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenAnswer(invocation -> {
            BatchGetItemRequest request = invocation.getArgument(0);
            requests.add(request);
            UUID returned = requests.size() == 1 ? ID : new UUID(0, 101);
            return BatchGetItemResponse.builder().responses(Map.of(TABLE, List.of(item(returned)))).build();
        });

        assertThat(noWaitingStore().findScans(ids)).containsOnlyKeys(ID, new UUID(0, 101));

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).requestItems().get(TABLE).keys()).hasSize(100).doesNotHaveDuplicates();
        assertThat(requests.get(1).requestItems()).isEqualTo(pending(new UUID(0, 101)));
        assertThat(requests).allSatisfy(request -> assertThat(request.requestItems().get(TABLE).consistentRead()).isTrue());
    }

    @Test
    void resetsThreeAttemptAndDelayBudgetsForTheNextChunk() {
        List<UUID> ids = IntStream.rangeClosed(1, 101).mapToObj(i -> new UUID(0, i)).toList();
        List<Long> waits = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenAnswer(invocation -> {
            BatchGetItemRequest request = invocation.getArgument(0);
            sizes.add(request.requestItems().get(TABLE).keys().size());
            return sizes.size() % 3 == 0 ? BatchGetItemResponse.builder().build()
                    : BatchGetItemResponse.builder().unprocessedKeys(request.requestItems()).build();
        });
        var store = new DynamoDbProcessingResultStore(client, TABLE, 100_000, waits::add, maximum -> maximum);

        assertThat(store.findScans(ids)).isEmpty();

        assertThat(sizes).containsExactly(100, 100, 100, 1, 1, 1);
        assertThat(waits).containsExactly(50L, 100L, 50L, 100L);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 429, 503})
    void preservesSdkErrorClassificationWithoutApplicationRetries(int status) {
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenThrow(DynamoDbException.builder()
                .statusCode(status).awsErrorDetails(AwsErrorDetails.builder().errorCode("FixtureError").build()).build());

        assertThatThrownBy(() -> noWaitingStore().findScans(List.of(ID)))
                .isInstanceOf(status == 400 ? ProcessingStoreConflictException.class : TransientProcessingStoreException.class);
        verify(client).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void interruptionRestoresFlagAndStopsBeforeTheNextSdkCall() {
        InterruptedException interrupted = new InterruptedException("fixture cancellation");
        AtomicInteger waits = new AtomicInteger();
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenReturn(
                BatchGetItemResponse.builder().unprocessedKeys(pending(ID)).build());
        var store = new DynamoDbProcessingResultStore(client, TABLE, 100_000, delay -> {
            waits.incrementAndGet();
            throw interrupted;
        }, maximum -> maximum);

        try {
            assertThatThrownBy(() -> store.findScans(List.of(ID))).isInstanceOf(TransientProcessingStoreException.class)
                    .hasCause(interrupted);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(waits.get()).isEqualTo(1);
            verify(client).batchGetItem(any(BatchGetItemRequest.class));
        } finally {
            Thread.interrupted();
        }
    }

    private DynamoDbProcessingResultStore noWaitingStore() {
        return new DynamoDbProcessingResultStore(client, TABLE, 100_000,
                delay -> { throw new AssertionError("Unexpected wait"); },
                maximum -> { throw new AssertionError("Unexpected jitter draw"); });
    }

    private static Map<String, KeysAndAttributes> pending(UUID... ids) {
        return Map.of(TABLE, KeysAndAttributes.builder().consistentRead(true)
                .keys(java.util.Arrays.stream(ids).map(id -> Map.of("pk", text("SCAN#" + id), "sk", text("META"))).toList())
                .build());
    }

    private static Map<String, AttributeValue> item(UUID id) {
        return Map.of("scanId", text(id.toString()), "assetId", text(id.toString()), "status", text("COMPLETED"));
    }

    private static AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
