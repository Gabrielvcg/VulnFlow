package com.vulnflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetRepository;
import com.vulnflow.asset.AssetType;
import com.vulnflow.aws.ingestion.AwsOutboxPublisher;
import com.vulnflow.aws.lambda.SqsVulnerabilityReportHandler;
import com.vulnflow.contract.IngestionEventJsonCodec;
import com.vulnflow.contract.IngestionEventV1;
import com.vulnflow.processing.ProcessedVulnerabilityReport;
import com.vulnflow.processing.VulnerabilityReportProcessor;
import com.vulnflow.processing.port.IngestionMessagePublisher;
import com.vulnflow.processing.port.ProcessingFailure;
import com.vulnflow.processing.port.ProcessingFindingPage;
import com.vulnflow.processing.port.ProcessingFindingResult;
import com.vulnflow.processing.port.ProcessingResultReader;
import com.vulnflow.processing.port.ProcessingResultStatus;
import com.vulnflow.processing.port.ProcessingResultStore;
import com.vulnflow.processing.port.ProcessingResultSummary;
import com.vulnflow.processing.port.ProcessingStoreOutcome;
import com.vulnflow.processing.port.ReportStorage;
import com.vulnflow.processing.port.TransientReportStorageException;
import com.vulnflow.scan.ScanRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

@SpringBootTest(properties = {
    "vulnflow.security.api-key.value=test-api-key",
    "vulnflow.aws.region=eu-west-1",
    "vulnflow.aws.s3-bucket=vulnflow-demo-reports",
    "vulnflow.aws.s3-prefix=reports",
    "vulnflow.aws.sqs-queue-url=https://sqs.eu-west-1.amazonaws.com/123456789012/vulnflow-demo",
    "vulnflow.aws.dynamodb-table=vulnflow-demo-results",
    "vulnflow.aws.dynamodb-max-findings=100000",
    "vulnflow.aws.max-payload-bytes=10485760",
    "vulnflow.aws.api-call-timeout=2s",
    "vulnflow.aws.connection-timeout=1s",
    "vulnflow.aws.outbox.enabled=false",
    "vulnflow.aws.outbox.poll-interval=1h",
    "vulnflow.aws.outbox.batch-size=10",
    "vulnflow.aws.outbox.max-attempts=5",
    "vulnflow.aws.outbox.stale-timeout=2m",
    "vulnflow.aws.outbox.backoff=1ms"
})
@ActiveProfiles("aws")
@AutoConfigureMockMvc
@Testcontainers
@Import(AwsExecutionPathIT.FakeAwsConfiguration.class)
class AwsExecutionPathIT {
    private static final String API_KEY = "test-api-key";
    private static final byte[] REPORT = ("""
            {"Scanner":{"Version":"0.60.0"},"Results":[{"Vulnerabilities":[{
              "VulnerabilityID":"CVE-2026-0001","PkgName":"openssl","InstalledVersion":"1",
              "FixedVersion":"2","Severity":"CRITICAL","Title":"Demo","Description":"Demo finding"
            }]}]}
            """).getBytes(StandardCharsets.UTF_8);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AssetRepository assetRepository;
    @Autowired ScanRepository scanRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AwsOutboxPublisher outboxPublisher;
    @Autowired VulnerabilityReportProcessor processor;
    @Autowired InMemoryAwsServices aws;

    @MockitoBean S3Client s3Client;
    @MockitoBean SqsClient sqsClient;
    @MockitoBean DynamoDbClient dynamoDbClient;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM aws_publication_outbox");
        scanRepository.deleteAll();
        assetRepository.deleteAll();
        aws.clear();
    }

    @Test
    void executesTheAwsPathOfflineWithRetryIdempotencyPartialBatchAndQueries() throws Exception {
        mockMvc.perform(get("/api/v1/scans/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        Asset asset = assetRepository.save(new Asset("demo", AssetType.APPLICATION, "demo:aws"));
        MockMultipartFile report = new MockMultipartFile(
                "file", "trivy.json", MediaType.APPLICATION_JSON_VALUE, REPORT);

        MvcResult upload = mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(report)
                        .param("assetId", asset.getId().toString())
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.publicationStatus").value("PUBLISH_PENDING"))
                .andReturn();
        JsonNode response = objectMapper.readTree(upload.getResponse().getContentAsString());
        UUID scanId = UUID.fromString(response.path("scanId").asText());

        aws.failNextPublication.set(true);
        assertThat(outboxPublisher.pollOnce()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM aws_publication_outbox WHERE scan_id = ?",
                String.class,
                scanId)).isEqualTo("PUBLISH_PENDING");
        jdbcTemplate.update(
                "UPDATE aws_publication_outbox "
                        + "SET available_at = TIMESTAMPTZ '2000-01-01 00:00:00+00' WHERE scan_id = ?",
                scanId);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(outboxPublisher::pollOnce);
            var second = executor.submit(outboxPublisher::pollOnce);
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(aws.publishedEvents).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM aws_publication_outbox WHERE scan_id = ?",
                String.class,
                scanId)).isEqualTo("PUBLISHED");

        IngestionEventV1 event = aws.publishedEvents.get(0);
        SqsVulnerabilityReportHandler handler = new SqsVulnerabilityReportHandler(
                new IngestionEventJsonCodec(), aws, processor, aws);
        var firstDelivery = handler.handleRequest(batch(
                message("delivery-1", new IngestionEventJsonCodec().serialize(event))), null);
        var duplicateDelivery = handler.handleRequest(batch(
                message("delivery-2", new IngestionEventJsonCodec().serialize(event))), null);
        assertThat(firstDelivery.getBatchItemFailures()).isEmpty();
        assertThat(duplicateDelivery.getBatchItemFailures()).isEmpty();
        assertThat(aws.resultWrites).isEqualTo(1);

        IngestionEventV1 transientEvent = new IngestionEventV1(
                "1", UUID.randomUUID(), UUID.randomUUID(), asset.getId(), "transient/report.json",
                hash(REPORT), "TRIVY", Instant.now(), UUID.randomUUID());
        var mixed = handler.handleRequest(batch(
                message("already-complete", new IngestionEventJsonCodec().serialize(event)),
                message("retry-me", new IngestionEventJsonCodec().serialize(transientEvent))), null);
        assertThat(mixed.getBatchItemFailures())
                .extracting(item -> item.getItemIdentifier())
                .containsExactly("retry-me");

        mockMvc.perform(get("/api/v1/scans/{id}", scanId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.findingCount").value(1));
        mockMvc.perform(get("/api/v1/scans/{id}/findings", scanId)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findings[0].vulnerabilityId").value("CVE-2026-0001"));
        assertThat(aws.remoteCallInsideTransaction.get()).isFalse();
    }

    private com.amazonaws.services.lambda.runtime.events.SQSEvent batch(
            com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage... messages) {
        var batch = new com.amazonaws.services.lambda.runtime.events.SQSEvent();
        batch.setRecords(List.of(messages));
        return batch;
    }

    private com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage message(String id, String body) {
        var message = new com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage();
        message.setMessageId(id);
        message.setBody(body);
        return message;
    }

    private static String hash(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @TestConfiguration
    static class FakeAwsConfiguration {
        @Bean
        @Primary
        InMemoryAwsServices inMemoryAwsServices() {
            return new InMemoryAwsServices();
        }
    }

    static final class InMemoryAwsServices implements ReportStorage,
            IngestionMessagePublisher,
            ProcessingResultStore<IngestionEventV1>,
            ProcessingResultReader {
        private final Map<String, byte[]> payloads = new ConcurrentHashMap<>();
        private final Map<UUID, ProcessingResultSummary> summaries = new ConcurrentHashMap<>();
        private final Map<UUID, List<ProcessingFindingResult>> findings = new ConcurrentHashMap<>();
        private final List<IngestionEventV1> publishedEvents = new ArrayList<>();
        private final AtomicBoolean failNextPublication = new AtomicBoolean();
        private final AtomicBoolean remoteCallInsideTransaction = new AtomicBoolean();
        private int resultWrites;

        @Override
        public String store(UUID scanId, byte[] content) {
            checkOutsideTransaction();
            String key = "reports/" + scanId + "/report.json";
            payloads.put(key, content.clone());
            return key;
        }

        @Override
        public byte[] load(String payloadKey) {
            checkOutsideTransaction();
            if (payloadKey.startsWith("transient/")) {
                throw new TransientReportStorageException("temporary", null);
            }
            byte[] content = payloads.get(payloadKey);
            if (content == null) {
                throw new IllegalStateException("payload missing");
            }
            return content.clone();
        }

        @Override
        public void delete(String payloadKey) {
            checkOutsideTransaction();
            payloads.remove(payloadKey);
        }

        @Override
        public boolean exists(String payloadKey) {
            checkOutsideTransaction();
            return payloads.containsKey(payloadKey);
        }

        @Override
        public String publish(IngestionEventV1 event) {
            checkOutsideTransaction();
            if (failNextPublication.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated publication failure");
            }
            synchronized (publishedEvents) {
                publishedEvents.add(event);
            }
            return "message-" + event.eventId();
        }

        @Override
        public boolean isFinalized(IngestionEventV1 event) {
            ProcessingResultSummary summary = summaries.get(event.scanId());
            return summary != null
                    && (summary.status() == ProcessingResultStatus.COMPLETED
                    || summary.status() == ProcessingResultStatus.FAILED);
        }

        @Override
        public ProcessingStoreOutcome store(IngestionEventV1 event, ProcessedVulnerabilityReport report) {
            if (isFinalized(event)) {
                return ProcessingStoreOutcome.DUPLICATE;
            }
            List<ProcessingFindingResult> storedFindings = report.findings().stream()
                    .map(finding -> new ProcessingFindingResult(
                            "FINDING#00000000",
                            finding.vulnerabilityId(),
                            finding.packageName(),
                            finding.installedVersion(),
                            finding.fixedVersion(),
                            finding.severity(),
                            finding.title(),
                            finding.description(),
                            finding.knownExploited(),
                            finding.riskScore()))
                    .toList();
            findings.put(event.scanId(), storedFindings);
            summaries.put(event.scanId(), new ProcessingResultSummary(
                    event.eventId(), event.scanId(), event.assetId(), event.correlationId(), event.contentHash(),
                    event.scanner(), report.scannerVersion(), ProcessingResultStatus.COMPLETED,
                    event.createdAt(), Instant.now(), storedFindings.size(), Map.of("CRITICAL", storedFindings.size()),
                    null, null));
            resultWrites++;
            return ProcessingStoreOutcome.STORED;
        }

        @Override
        public ProcessingStoreOutcome storeFailure(IngestionEventV1 event, ProcessingFailure failure) {
            summaries.put(event.scanId(), new ProcessingResultSummary(
                    event.eventId(), event.scanId(), event.assetId(), event.correlationId(), event.contentHash(),
                    event.scanner(), null, ProcessingResultStatus.FAILED, event.createdAt(), failure.failedAt(),
                    0, Map.of(), failure.code(), failure.safeMessage()));
            return ProcessingStoreOutcome.STORED;
        }

        @Override
        public Optional<ProcessingResultSummary> findScan(UUID scanId) {
            checkOutsideTransaction();
            return Optional.ofNullable(summaries.get(scanId));
        }

        @Override
        public ProcessingFindingPage findFindings(UUID scanId, String cursor, int size) {
            checkOutsideTransaction();
            return new ProcessingFindingPage(findings.getOrDefault(scanId, List.of()), null);
        }

        void clear() {
            payloads.clear();
            summaries.clear();
            findings.clear();
            synchronized (publishedEvents) {
                publishedEvents.clear();
            }
            failNextPublication.set(false);
            remoteCallInsideTransaction.set(false);
            resultWrites = 0;
        }

        private void checkOutsideTransaction() {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                remoteCallInsideTransaction.set(true);
                throw new IllegalStateException("A simulated AWS call was made inside a PostgreSQL transaction");
            }
        }
    }
}
