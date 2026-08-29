package com.vulnflow.aws.dynamodb;

import com.vulnflow.contract.IngestionEventV1;
import com.vulnflow.processing.FindingSeverity;
import com.vulnflow.processing.NormalizedFinding;
import com.vulnflow.processing.ProcessedVulnerabilityReport;
import com.vulnflow.processing.port.ProcessingFailure;
import com.vulnflow.processing.port.ProcessingFindingPage;
import com.vulnflow.processing.port.ProcessingFindingResult;
import com.vulnflow.processing.port.ProcessingResultReader;
import com.vulnflow.processing.port.ProcessingResultStatus;
import com.vulnflow.processing.port.ProcessingResultStore;
import com.vulnflow.processing.port.ProcessingResultSummary;
import com.vulnflow.processing.port.ProcessingStoreConflictException;
import com.vulnflow.processing.port.ProcessingStoreOutcome;
import com.vulnflow.processing.port.TransientProcessingStoreException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

public final class DynamoDbProcessingResultStore
        implements ProcessingResultStore<IngestionEventV1>, ProcessingResultReader {
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_.-]{3,255}");
    private static final String PK = "pk";
    private static final String SK = "sk";
    private static final String META = "META";
    private static final String STATUS_WRITING = "WRITING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final int DYNAMODB_BATCH_LIMIT = 25;
    private static final int DYNAMODB_BATCH_GET_LIMIT = 100;
    private static final int MAX_CURSOR_LENGTH = 1024;

    private final DynamoDbClient client;
    private final String tableName;
    private final int maximumFindings;

    public DynamoDbProcessingResultStore(DynamoDbClient client, String tableName, int maximumFindings) {
        this.client = Objects.requireNonNull(client, "client");
        if (tableName == null || !TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName is invalid");
        }
        if (maximumFindings < 1 || maximumFindings > 100_000) {
            throw new IllegalArgumentException("maximumFindings is outside the supported range");
        }
        this.tableName = tableName;
        this.maximumFindings = maximumFindings;
    }

    @Override
    public boolean isFinalized(IngestionEventV1 event) {
        Objects.requireNonNull(event, "event");
        EventState state = loadEvent(event.eventId()).orElse(null);
        if (state == null) {
            return false;
        }
        assertSameEvent(event, state);
        return STATUS_COMPLETED.equals(state.status()) || STATUS_FAILED.equals(state.status());
    }

    @Override
    public ProcessingStoreOutcome store(IngestionEventV1 event, ProcessedVulnerabilityReport report) {
        validateReport(event, report);
        EventState state = loadEvent(event.eventId()).orElse(null);
        if (state != null) {
            ProcessingStoreOutcome outcome = existingOutcome(event, state, report.findings().size());
            if (outcome != null) {
                return outcome;
            }
        } else {
            if (beginCompletion(event, report)) {
                return ProcessingStoreOutcome.DUPLICATE;
            }
        }

        writeFindings(event, report.findings());
        return finalizeCompletion(event, report);
    }

    @Override
    public ProcessingStoreOutcome storeFailure(IngestionEventV1 event, ProcessingFailure failure) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(failure, "failure");
        EventState existing = loadEvent(event.eventId()).orElse(null);
        if (existing != null) {
            assertSameEvent(event, existing);
            if (STATUS_FAILED.equals(existing.status()) || STATUS_COMPLETED.equals(existing.status())) {
                return ProcessingStoreOutcome.DUPLICATE;
            }
            throw new ProcessingStoreConflictException("The event already has an incomplete completion attempt");
        }

        Map<String, AttributeValue> eventItem = baseEventItem(event, STATUS_FAILED, 0);
        eventItem.put("completedAt", string(failure.failedAt().toString()));
        eventItem.put("errorCode", string(failure.code()));
        eventItem.put("safeError", string(failure.safeMessage()));

        Map<String, AttributeValue> scanItem = baseScanItem(event, STATUS_FAILED, 0);
        scanItem.put("completedAt", string(failure.failedAt().toString()));
        scanItem.put("errorCode", string(failure.code()));
        scanItem.put("safeError", string(failure.safeMessage()));

        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            conditionalPut(eventItem),
                            conditionalPut(scanItem))
                    .build());
            return ProcessingStoreOutcome.STORED;
        } catch (TransactionCanceledException exception) {
            EventState raced = loadEvent(event.eventId()).orElse(null);
            if (raced != null) {
                assertSameEvent(event, raced);
                if (STATUS_FAILED.equals(raced.status()) || STATUS_COMPLETED.equals(raced.status())) {
                    return ProcessingStoreOutcome.DUPLICATE;
                }
            }
            throw mapTransactionFailure("The failed result could not be committed", exception);
        } catch (SdkException exception) {
            throw mapSdkFailure("The failed result could not be committed", exception);
        }
    }

    @Override
    public Optional<ProcessingResultSummary> findScan(UUID scanId) {
        Objects.requireNonNull(scanId, "scanId");
        Map<String, AttributeValue> item = getItem(scanPk(scanId), META, true);
        if (item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toSummary(item));
    }

    @Override
    public Map<UUID, ProcessingResultSummary> findScans(Collection<UUID> scanIds) {
        Objects.requireNonNull(scanIds, "scanIds");
        List<UUID> uniqueIds = new ArrayList<>(new LinkedHashSet<>(scanIds));
        Map<UUID, ProcessingResultSummary> results = new LinkedHashMap<>();
        try {
            for (int offset = 0; offset < uniqueIds.size(); offset += DYNAMODB_BATCH_GET_LIMIT) {
                List<UUID> batch = uniqueIds.subList(offset, Math.min(offset + DYNAMODB_BATCH_GET_LIMIT, uniqueIds.size()));
                Map<String, KeysAndAttributes> pending = Map.of(tableName, KeysAndAttributes.builder()
                        .consistentRead(true)
                        .keys(batch.stream().map(id -> key(scanPk(id), META)).toList())
                        .build());
                for (int attempt = 0; !pending.isEmpty() && attempt < 3; attempt++) {
                    var response = client.batchGetItem(BatchGetItemRequest.builder().requestItems(pending).build());
                    for (Map<String, AttributeValue> item : response.responses().getOrDefault(tableName, List.of())) {
                        ProcessingResultSummary summary = toSummary(item);
                        results.put(summary.scanId(), summary);
                    }
                    pending = response.unprocessedKeys();
                }
                if (!pending.isEmpty()) {
                    throw new TransientProcessingStoreException("The result summary batch was not fully read", null);
                }
            }
            return results;
        } catch (SdkException exception) {
            throw mapSdkFailure("The result summary batch could not be read", exception);
        }
    }

    @Override
    public ProcessingFindingPage findFindings(UUID scanId, String cursor, int size) {
        Objects.requireNonNull(scanId, "scanId");
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        Optional<ProcessingResultSummary> summary = findScan(scanId);
        if (summary.isEmpty() || summary.get().status() != ProcessingResultStatus.COMPLETED) {
            return new ProcessingFindingPage(List.of(), null);
        }

        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", PK, "#sk", SK))
                .expressionAttributeValues(Map.of(
                        ":pk", string(scanPk(scanId)),
                        ":prefix", string("FINDING#")))
                .limit(size)
                .consistentRead(true);
        String decodedCursor = decodeCursor(cursor);
        if (decodedCursor != null) {
            request.exclusiveStartKey(key(scanPk(scanId), decodedCursor));
        }
        try {
            QueryResponse response = client.query(request.build());
            List<ProcessingFindingResult> findings = response.items().stream()
                    .map(this::toFinding)
                    .toList();
            String nextCursor = response.lastEvaluatedKey().isEmpty()
                    ? null
                    : encodeCursor(requiredString(response.lastEvaluatedKey(), SK));
            return new ProcessingFindingPage(findings, nextCursor);
        } catch (SdkException exception) {
            throw mapSdkFailure("The findings could not be read", exception);
        }
    }

    private boolean beginCompletion(IngestionEventV1 event, ProcessedVulnerabilityReport report) {
        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            conditionalPut(baseEventItem(event, STATUS_WRITING, report.findings().size())),
                            conditionalPut(baseScanItem(event, STATUS_WRITING, report.findings().size())))
                    .build());
            return false;
        } catch (TransactionCanceledException exception) {
            EventState raced = loadEvent(event.eventId()).orElse(null);
            if (raced != null) {
                ProcessingStoreOutcome outcome = existingOutcome(event, raced, report.findings().size());
                if (outcome == ProcessingStoreOutcome.DUPLICATE) {
                    return true;
                }
                return false;
            }
            if (!getItem(scanPk(event.scanId()), META, true).isEmpty()) {
                throw new ProcessingStoreConflictException("The scan already belongs to another event");
            }
            throw mapTransactionFailure("The result write could not be started", exception);
        } catch (SdkException exception) {
            throw mapSdkFailure("The result write could not be started", exception);
        }
    }

    private void writeFindings(IngestionEventV1 event, List<NormalizedFinding> findings) {
        for (int offset = 0; offset < findings.size(); offset += DYNAMODB_BATCH_LIMIT) {
            int end = Math.min(offset + DYNAMODB_BATCH_LIMIT, findings.size());
            List<WriteRequest> writes = new ArrayList<>(end - offset);
            for (int index = offset; index < end; index++) {
                writes.add(WriteRequest.builder()
                        .putRequest(PutRequest.builder()
                                .item(findingItem(event, findings.get(index), index))
                                .build())
                        .build());
            }
            try {
                BatchWriteItemResponse response = client.batchWriteItem(BatchWriteItemRequest.builder()
                        .requestItems(Map.of(tableName, writes))
                        .build());
                if (!response.unprocessedItems().getOrDefault(tableName, List.of()).isEmpty()) {
                    throw new TransientProcessingStoreException(
                            "DynamoDB did not process every finding write", null);
                }
            } catch (TransientProcessingStoreException exception) {
                throw exception;
            } catch (SdkException exception) {
                throw mapSdkFailure("The finding batch could not be written", exception);
            }
        }
    }

    private ProcessingStoreOutcome finalizeCompletion(
            IngestionEventV1 event,
            ProcessedVulnerabilityReport report) {
        Instant completedAt = Instant.now();
        Map<FindingSeverity, Integer> counts = severityCounts(report.findings());
        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            TransactWriteItem.builder().update(eventCompletionUpdate(event, completedAt)).build(),
                            TransactWriteItem.builder().update(scanCompletionUpdate(event, report, counts, completedAt)).build())
                    .build());
            return ProcessingStoreOutcome.STORED;
        } catch (TransactionCanceledException exception) {
            EventState state = loadEvent(event.eventId()).orElse(null);
            if (state != null) {
                assertSameEvent(event, state);
                if (STATUS_COMPLETED.equals(state.status())) {
                    return ProcessingStoreOutcome.DUPLICATE;
                }
            }
            throw mapTransactionFailure("The result completion could not be committed", exception);
        } catch (SdkException exception) {
            throw mapSdkFailure("The result completion could not be committed", exception);
        }
    }

    private Update eventCompletionUpdate(IngestionEventV1 event, Instant completedAt) {
        return Update.builder()
                .tableName(tableName)
                .key(key(eventPk(event.eventId()), META))
                .updateExpression("SET #status = :completed, completedAt = :now, updatedAt = :now")
                .conditionExpression("#status = :writing AND contentHash = :hash AND scanId = :scanId")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":completed", string(STATUS_COMPLETED),
                        ":writing", string(STATUS_WRITING),
                        ":now", string(completedAt.toString()),
                        ":hash", string(event.contentHash()),
                        ":scanId", string(event.scanId().toString())))
                .build();
    }

    private Update scanCompletionUpdate(
            IngestionEventV1 event,
            ProcessedVulnerabilityReport report,
            Map<FindingSeverity, Integer> counts,
            Instant completedAt) {
        Map<String, String> names = new HashMap<>();
        names.put("#status", "status");
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":completed", string(STATUS_COMPLETED));
        values.put(":writing", string(STATUS_WRITING));
        values.put(":now", string(completedAt.toString()));
        values.put(":eventId", string(event.eventId().toString()));
        values.put(":scannerVersion", string(nullToEmpty(report.scannerVersion())));
        values.put(":gsiPk", string("ASSET#" + event.assetId()));
        values.put(":gsiSk", string("COMPLETED#" + completedAt + "#SCAN#" + event.scanId()));
        StringBuilder expression = new StringBuilder(
                "SET #status = :completed, completedAt = :now, updatedAt = :now, "
                        + "scannerVersion = :scannerVersion, gsi1pk = :gsiPk, gsi1sk = :gsiSk");
        for (FindingSeverity severity : FindingSeverity.values()) {
            String token = severity.name().toLowerCase(Locale.ROOT);
            expression.append(", severity_").append(token).append(" = :").append(token);
            values.put(":" + token, number(counts.getOrDefault(severity, 0)));
        }
        return Update.builder()
                .tableName(tableName)
                .key(key(scanPk(event.scanId()), META))
                .updateExpression(expression.toString())
                .conditionExpression("#status = :writing AND eventId = :eventId")
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .build();
    }

    private ProcessingStoreOutcome existingOutcome(
            IngestionEventV1 event,
            EventState state,
            int findingCount) {
        assertSameEvent(event, state);
        if (state.findingCount() != findingCount) {
            throw new ProcessingStoreConflictException(
                    "The event was retried with a different normalized finding count");
        }
        if (STATUS_COMPLETED.equals(state.status()) || STATUS_FAILED.equals(state.status())) {
            return ProcessingStoreOutcome.DUPLICATE;
        }
        if (STATUS_WRITING.equals(state.status())) {
            return null;
        }
        throw new ProcessingStoreConflictException("The event has an unsupported persisted state");
    }

    private void validateReport(IngestionEventV1 event, ProcessedVulnerabilityReport report) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(report, "report");
        if (!event.scanId().equals(report.scanId()) || !event.assetId().equals(report.assetId())) {
            throw new ProcessingStoreConflictException("The processed report identity does not match the event");
        }
        if (report.findings().size() > maximumFindings) {
            throw new ProcessingStoreConflictException("The report exceeds the configured finding limit");
        }
    }

    private Map<String, AttributeValue> baseEventItem(
            IngestionEventV1 event,
            String status,
            int findingCount) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put(PK, string(eventPk(event.eventId())));
        item.put(SK, string(META));
        item.put("itemType", string("EVENT"));
        addIdentity(item, event);
        item.put("status", string(status));
        item.put("findingCount", number(findingCount));
        item.put("receivedAt", string(event.createdAt().toString()));
        item.put("updatedAt", string(Instant.now().toString()));
        return item;
    }

    private Map<String, AttributeValue> baseScanItem(
            IngestionEventV1 event,
            String status,
            int findingCount) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put(PK, string(scanPk(event.scanId())));
        item.put(SK, string(META));
        item.put("itemType", string("SCAN"));
        addIdentity(item, event);
        item.put("status", string(status));
        item.put("findingCount", number(findingCount));
        item.put("receivedAt", string(event.createdAt().toString()));
        item.put("updatedAt", string(Instant.now().toString()));
        return item;
    }

    private void addIdentity(Map<String, AttributeValue> item, IngestionEventV1 event) {
        item.put("eventId", string(event.eventId().toString()));
        item.put("scanId", string(event.scanId().toString()));
        item.put("assetId", string(event.assetId().toString()));
        item.put("correlationId", string(event.correlationId().toString()));
        item.put("contentHash", string(event.contentHash()));
        item.put("scanner", string(event.scanner()));
    }

    private Map<String, AttributeValue> findingItem(
            IngestionEventV1 event,
            NormalizedFinding finding,
            int index) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put(PK, string(scanPk(event.scanId())));
        item.put(SK, string(String.format(Locale.ROOT, "FINDING#%08d", index)));
        item.put("itemType", string("FINDING"));
        item.put("eventId", string(event.eventId().toString()));
        item.put("scanId", string(event.scanId().toString()));
        item.put("assetId", string(event.assetId().toString()));
        putNullable(item, "vulnerabilityId", finding.vulnerabilityId());
        putNullable(item, "packageName", finding.packageName());
        putNullable(item, "installedVersion", finding.installedVersion());
        putNullable(item, "fixedVersion", finding.fixedVersion());
        item.put("severity", string(finding.severity().name()));
        putNullable(item, "title", finding.title());
        putNullable(item, "description", finding.description());
        item.put("knownExploited", AttributeValue.builder().bool(finding.knownExploited()).build());
        item.put("riskScore", number(finding.riskScore()));
        return item;
    }

    private TransactWriteItem conditionalPut(Map<String, AttributeValue> item) {
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(item)
                        .conditionExpression("attribute_not_exists(#pk) AND attribute_not_exists(#sk)")
                        .expressionAttributeNames(Map.of("#pk", PK, "#sk", SK))
                        .build())
                .build();
    }

    private Optional<EventState> loadEvent(UUID eventId) {
        Map<String, AttributeValue> item = getItem(eventPk(eventId), META, true);
        if (item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EventState(
                UUID.fromString(requiredString(item, "eventId")),
                UUID.fromString(requiredString(item, "scanId")),
                UUID.fromString(requiredString(item, "assetId")),
                requiredString(item, "contentHash"),
                requiredString(item, "scanner"),
                requiredString(item, "status"),
                requiredInt(item, "findingCount")));
    }

    private Map<String, AttributeValue> getItem(String pk, String sk, boolean consistent) {
        try {
            GetItemResponse response = client.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(key(pk, sk))
                    .consistentRead(consistent)
                    .build());
            return response.hasItem() ? response.item() : Map.of();
        } catch (SdkException exception) {
            throw mapSdkFailure("The processing result could not be read", exception);
        }
    }

    private ProcessingResultSummary toSummary(Map<String, AttributeValue> item) {
        String persistedStatus = requiredString(item, "status");
        ProcessingResultStatus status = switch (persistedStatus) {
            case STATUS_WRITING -> ProcessingResultStatus.PROCESSING;
            case STATUS_COMPLETED -> ProcessingResultStatus.COMPLETED;
            case STATUS_FAILED -> ProcessingResultStatus.FAILED;
            default -> throw new ProcessingStoreConflictException("DynamoDB contains an unsupported scan status");
        };
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (FindingSeverity severity : FindingSeverity.values()) {
            counts.put(severity.name(), optionalInt(item, "severity_" + severity.name().toLowerCase(Locale.ROOT), 0));
        }
        return new ProcessingResultSummary(
                optionalUuid(item, "eventId"),
                UUID.fromString(requiredString(item, "scanId")),
                UUID.fromString(requiredString(item, "assetId")),
                optionalUuid(item, "correlationId"),
                optionalString(item, "contentHash"),
                optionalString(item, "scanner"),
                optionalString(item, "scannerVersion"),
                status,
                optionalInstant(item, "receivedAt"),
                optionalInstant(item, "completedAt"),
                optionalInt(item, "findingCount", 0),
                counts,
                optionalString(item, "errorCode"),
                optionalString(item, "safeError"));
    }

    private ProcessingFindingResult toFinding(Map<String, AttributeValue> item) {
        return new ProcessingFindingResult(
                requiredString(item, SK),
                optionalString(item, "vulnerabilityId"),
                optionalString(item, "packageName"),
                optionalString(item, "installedVersion"),
                optionalString(item, "fixedVersion"),
                FindingSeverity.valueOf(requiredString(item, "severity")),
                optionalString(item, "title"),
                optionalString(item, "description"),
                item.getOrDefault("knownExploited", AttributeValue.builder().bool(false).build()).bool(),
                requiredInt(item, "riskScore"));
    }

    private void assertSameEvent(IngestionEventV1 event, EventState state) {
        if (!event.eventId().equals(state.eventId())
                || !event.scanId().equals(state.scanId())
                || !event.assetId().equals(state.assetId())
                || !event.contentHash().equals(state.contentHash())
                || !event.scanner().equals(state.scanner())) {
            throw new ProcessingStoreConflictException(
                    "The eventId is already associated with different content or identity");
        }
    }

    private RuntimeException mapTransactionFailure(String message, TransactionCanceledException exception) {
        if (isRetryable(exception)) {
            return new TransientProcessingStoreException(message, exception);
        }
        return new ProcessingStoreConflictException(message);
    }

    private RuntimeException mapSdkFailure(String message, SdkException exception) {
        if (isRetryable(exception)) {
            return new TransientProcessingStoreException(message, exception);
        }
        return new ProcessingStoreConflictException(message);
    }

    private boolean isRetryable(SdkException exception) {
        if (exception.retryable()) {
            return true;
        }
        if (exception instanceof DynamoDbException dynamo) {
            int statusCode = dynamo.statusCode();
            String code = dynamo.awsErrorDetails() == null ? "" : dynamo.awsErrorDetails().errorCode();
            return statusCode == 429 || statusCode >= 500
                    || code.contains("Throttl")
                    || code.equals("ProvisionedThroughputExceededException")
                    || code.equals("RequestLimitExceeded");
        }
        return false;
    }

    private Map<FindingSeverity, Integer> severityCounts(List<NormalizedFinding> findings) {
        Map<FindingSeverity, Integer> counts = new EnumMap<>(FindingSeverity.class);
        for (NormalizedFinding finding : findings) {
            counts.merge(finding.severity(), 1, Integer::sum);
        }
        return counts;
    }

    private String encodeCursor(String sk) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sk.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("cursor is too long");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.matches("FINDING#[0-9]{8}")) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cursor is invalid", exception);
        }
    }

    private static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of(PK, string(pk), SK, string(sk));
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(int value) {
        return AttributeValue.builder().n(Integer.toString(value)).build();
    }

    private static void putNullable(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, string(value));
        }
    }

    private static String requiredString(Map<String, AttributeValue> item, String name) {
        String value = optionalString(item, name);
        if (value == null) {
            throw new ProcessingStoreConflictException("DynamoDB item is missing " + name);
        }
        return value;
    }

    private static String optionalString(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    private static int requiredInt(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.n() == null) {
            throw new ProcessingStoreConflictException("DynamoDB item is missing " + name);
        }
        return Integer.parseInt(value.n());
    }

    private static int optionalInt(Map<String, AttributeValue> item, String name, int fallback) {
        AttributeValue value = item.get(name);
        return value == null || value.n() == null ? fallback : Integer.parseInt(value.n());
    }

    private static UUID optionalUuid(Map<String, AttributeValue> item, String name) {
        String value = optionalString(item, name);
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant optionalInstant(Map<String, AttributeValue> item, String name) {
        String value = optionalString(item, name);
        return value == null ? null : Instant.parse(value);
    }

    private static String eventPk(UUID eventId) {
        return "EVENT#" + eventId;
    }

    private static String scanPk(UUID scanId) {
        return "SCAN#" + scanId;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record EventState(
            UUID eventId,
            UUID scanId,
            UUID assetId,
            String contentHash,
            String scanner,
            String status,
            int findingCount) {
    }

}
