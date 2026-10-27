package com.vulnflow.aws.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

@ExtendWith(MockitoExtension.class)
class DynamoDbFindingsCompletionGuardTest {
    private static final UUID SCAN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String START = "FINDING#00000001";
    private static final String NEXT = "FINDING#00000002";
    @Mock
    private DynamoDbClient client;

    static Stream<Arguments> statesAndCursors() {
        return Stream.of("MISSING", "WRITING", "FAILED", "COMPLETED")
                .flatMap(state -> Stream.of(null, "", " \t ", encode(START))
                        .map(cursor -> Arguments.of(state, cursor)));
    }

    @ParameterizedTest(name = "state={0}, cursor={1}")
    @MethodSource("statesAndCursors")
    void exposesFindingsOnlyAfterCompletionOnEveryPage(String state, String cursor) {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(summary(state));
        lenient().when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(Map.of("sk", text(NEXT), "severity", text("LOW"), "riskScore", number(20)))
                .lastEvaluatedKey(key(NEXT)).build());

        var page = store().findFindings(SCAN, cursor, 25);

        if (!state.equals("COMPLETED")) {
            assertThat(page.findings()).isEmpty();
            assertThat(page.nextCursor()).isNull();
            verify(client, never()).query(any(QueryRequest.class));
        } else {
            assertThat(page.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.findingKey()).isEqualTo(NEXT);
                assertThat(finding.riskScore()).isEqualTo(20);
            });
            assertThat(page.nextCursor()).isEqualTo(encode(NEXT));
            ArgumentCaptor<QueryRequest> query = ArgumentCaptor.forClass(QueryRequest.class);
            verify(client).query(query.capture());
            assertThat(query.getValue().consistentRead()).isTrue();
            assertThat(query.getValue().limit()).isEqualTo(25);
            assertThat(query.getValue().expressionAttributeValues().get(":pk")).isEqualTo(text("SCAN#" + SCAN));
            assertThat(query.getValue().expressionAttributeValues().get(":prefix")).isEqualTo(text("FINDING#"));
            assertThat(query.getValue().exclusiveStartKey())
                    .isEqualTo(cursor == null || cursor.isBlank() ? Map.of() : key(START));
        }
        ArgumentCaptor<GetItemRequest> get = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(get.capture());
        assertThat(get.getValue().consistentRead()).isTrue();
        assertThat(get.getValue().key()).isEqualTo(key("META"));
    }

    static Stream<Arguments> statesAndInvalidCursors() {
        return Stream.of("MISSING", "WRITING", "FAILED", "COMPLETED")
                .flatMap(state -> Stream.of("%invalid", encode("META"), "A".repeat(1025))
                        .map(cursor -> Arguments.of(state, cursor)));
    }

    @ParameterizedTest(name = "invalid cursor with state={0}")
    @MethodSource("statesAndInvalidCursors")
    void rejectsMalformedAndOversizedCursorsBeforeReadingAnyState(String state, String cursor) {
        // No response is needed: invalid cursors must fail before either metadata or finding reads.
        lenient().when(client.getItem(any(GetItemRequest.class))).thenReturn(summary(state));
        assertThatThrownBy(() -> store().findFindings(SCAN, cursor, 25))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void rejectsOutOfBoundsPageSizesBeforeReading(int size) {
        assertThatThrownBy(() -> store().findFindings(SCAN, encode(START), size))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void acceptsBoundaryPageSizesAndEndsPaginationWhenNoKeyRemains(int size) {
        when(client.getItem(any(GetItemRequest.class))).thenReturn(summary("COMPLETED"));
        when(client.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().build());

        var page = store().findFindings(SCAN, encode(START), size);

        assertThat(page.findings()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        ArgumentCaptor<QueryRequest> query = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(query.capture());
        assertThat(query.getValue().limit()).isEqualTo(size);
        assertThat(query.getValue().exclusiveStartKey()).isEqualTo(key(START));
        assertThat(query.getValue().consistentRead()).isTrue();
    }

    private DynamoDbProcessingResultStore store() {
        return new DynamoDbProcessingResultStore(client, "vulnflow-results", 100_000);
    }

    private static GetItemResponse summary(String state) {
        return GetItemResponse.builder().item(state.equals("MISSING") ? Map.of() : Map.of(
                "status", text(state), "scanId", text(SCAN.toString()), "assetId", text(SCAN.toString()))).build();
    }

    private static Map<String, AttributeValue> key(String sk) {
        return Map.of("pk", text("SCAN#" + SCAN), "sk", text(sk));
    }

    private static String encode(String sk) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sk.getBytes(StandardCharsets.UTF_8));
    }

    private static AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(int value) {
        return AttributeValue.builder().n(Integer.toString(value)).build();
    }
}
