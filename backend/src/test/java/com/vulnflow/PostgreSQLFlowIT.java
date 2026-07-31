package com.vulnflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetRepository;
import com.vulnflow.asset.AssetType;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.Finding;
import com.vulnflow.finding.FindingSeverity;
import com.vulnflow.ingestion.FailureDisposition;
import com.vulnflow.ingestion.IngestionJob;
import com.vulnflow.ingestion.IngestionJobRecoveryService;
import com.vulnflow.ingestion.IngestionJobRedriveService;
import com.vulnflow.ingestion.IngestionJobRepository;
import com.vulnflow.ingestion.IngestionJobStatus;
import com.vulnflow.ingestion.IngestionPersistenceService;
import com.vulnflow.ingestion.JobClaim;
import com.vulnflow.ingestion.JobClaimService;
import com.vulnflow.ingestion.JobFailureService;
import com.vulnflow.ingestion.LocalFileReportStorage;
import com.vulnflow.ingestion.LocalIngestionWorker;
import com.vulnflow.ingestion.ParsedVulnerability;
import com.vulnflow.ingestion.ParsedVulnerabilityReport;
import com.vulnflow.ingestion.RecoveryResult;
import com.vulnflow.ingestion.ReportStorage;
import com.vulnflow.ingestion.ReportStorageException;
import com.vulnflow.ingestion.ReportStorageProperties;
import com.vulnflow.ingestion.StaleJobClaimException;
import com.vulnflow.ingestion.TransientReportStorageException;
import com.vulnflow.ingestion.VulnerabilityReportParser;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScanStatus;
import com.vulnflow.security.ApiKeyAuthenticationFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "vulnflow.security.api-key.value=test-api-key",
    "vulnflow.worker.enabled=false",
    "vulnflow.worker.poll-interval=1h",
    "vulnflow.worker.batch-size=5",
    "vulnflow.worker.max-attempts=3",
    "vulnflow.worker.stale-timeout=15m",
    "vulnflow.worker.backoff=5s,30s,2m"
})
@AutoConfigureMockMvc
@Testcontainers
class PostgreSQLFlowIT {

    private static final String API_KEY = "test-api-key";
    private static final Path REPORT_DIRECTORY = createReportDirectory();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine");

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("vulnflow.report-storage.directory", REPORT_DIRECTORY::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AssetRepository assetRepository;
    @Autowired ScanRepository scanRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired IngestionJobRepository jobRepository;
    @Autowired LocalIngestionWorker worker;
    @Autowired JobClaimService claimService;
    @Autowired IngestionJobRecoveryService recoveryService;
    @Autowired IngestionJobRedriveService redriveService;
    @Autowired IngestionPersistenceService persistenceService;
    @Autowired JobFailureService failureService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @MockitoSpyBean ReportStorage reportStorage;
    @MockitoSpyBean VulnerabilityReportParser reportParser;

    @BeforeEach
    void cleanState() throws Exception {
        jobRepository.deleteAll();
        findingRepository.deleteAll();
        scanRepository.deleteAll();
        assetRepository.deleteAll();
        if (Files.exists(REPORT_DIRECTORY)) {
            try (var paths = Files.walk(REPORT_DIRECTORY)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.equals(REPORT_DIRECTORY)) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    @Test
    void newUploadReturnsAcceptedWithPersistentPendingJob() throws Exception {
        UUID assetId = createAsset("accepted");

        JsonNode response = upload(assetId, readReport(), "report.json", 202);

        assertThat(response.path("scanId").asText()).isNotBlank();
        assertThat(response.path("jobId").asText()).isNotBlank();
        assertThat(response.path("scanStatus").asText()).isEqualTo("RECEIVED");
        assertThat(response.path("jobStatus").asText()).isEqualTo("PENDING");
        assertThat(response.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(scanRepository.count()).isOne();
        assertThat(jobRepository.count()).isOne();

        mockMvc.perform(get("/api/v1/ingestion-jobs/{id}", response.path("jobId").asText())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.payloadKey").doesNotExist());
    }

    @Test
    void workerCompletesAtomicallyAndCompletedUploadDeduplicates() throws Exception {
        UUID assetId = createAsset("completed");
        byte[] report = readReport();
        JsonNode accepted = upload(assetId, report, "first.json", 202);

        assertThat(worker.pollOnce()).isOne();

        UUID jobId = UUID.fromString(accepted.path("jobId").asText());
        UUID scanId = UUID.fromString(accepted.path("scanId").asText());
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.COMPLETED);
        assertThat(scanRepository.findById(scanId).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.COMPLETED);
        assertThat(findingRepository.countByScanId(scanId)).isEqualTo(3);

        JsonNode duplicate = upload(assetId, report, "different-name.json", 200);
        assertThat(duplicate.path("outcome").asText()).isEqualTo("DUPLICATE");
        assertThat(duplicate.path("scanId").asText()).isEqualTo(scanId.toString());
        assertThat(jobRepository.count()).isOne();
        assertThat(findingRepository.countByScanId(scanId)).isEqualTo(3);
    }

    @Test
    void modifiedPayloadFailsIntegrityBeforeParsingWithoutRetry() throws Exception {
        JsonNode accepted = upload(createAsset("integrity"), readReport(), "integrity.json", 202);
        UUID jobId = UUID.fromString(accepted.path("jobId").asText());
        UUID scanId = UUID.fromString(accepted.path("scanId").asText());
        IngestionJob job = jobRepository.findById(jobId).orElseThrow();
        Files.write(
                REPORT_DIRECTORY.resolve(job.getPayloadKey()),
                "{\"Results\":[]}".getBytes(StandardCharsets.UTF_8));
        clearInvocations(reportParser);

        worker.pollOnce();

        IngestionJob failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(IngestionJobStatus.DEAD_LETTER);
        assertThat(failed.getAttemptCount()).isOne();
        assertThat(failed.getLastError()).isEqualTo("Stored report payload integrity verification failed");
        assertThat(scanRepository.findById(scanId).orElseThrow().getStatus()).isEqualTo(ScanStatus.FAILED);
        assertThat(findingRepository.countByScanId(scanId)).isZero();
        verify(reportParser, never()).parse(any());
        mockMvc.perform(get("/api/v1/ingestion-jobs/{id}", jobId)
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadKey").doesNotExist())
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.contentHash").doesNotExist());
    }

    @Test
    void queuedDuplicateAndConcurrentUploadsCreateOnlyOneJobAndPayload() throws Exception {
        UUID assetId = createAsset("concurrent-upload");
        byte[] report = readReport();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> first = executor.submit(() -> {
                start.await();
                return upload(assetId, report, "one.json", 202);
            });
            Future<JsonNode> second = executor.submit(() -> {
                start.await();
                return upload(assetId, report, "two.json", 202);
            });
            start.countDown();

            List<JsonNode> responses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(responses).extracting(node -> node.path("outcome").asText())
                    .containsExactlyInAnyOrder("ACCEPTED", "ALREADY_QUEUED");
        } finally {
            executor.shutdownNow();
        }
        assertThat(scanRepository.count()).isOne();
        assertThat(jobRepository.count()).isOne();
        try (var files = Files.walk(REPORT_DIRECTORY)) {
            assertThat(files.filter(Files::isRegularFile)).hasSize(1);
        }
    }

    @Test
    void completedLegacyScanWithoutJobRemainsADuplicate() throws Exception {
        UUID assetId = createAsset("legacy-completed");
        byte[] report = readReport();
        UUID scanId = insertLegacyScan(assetId, report, "COMPLETED");

        JsonNode duplicate = upload(assetId, report, "legacy-completed.json", 200);

        assertThat(duplicate.path("outcome").asText()).isEqualTo("DUPLICATE");
        assertThat(duplicate.path("scanId").asText()).isEqualTo(scanId.toString());
        assertThat(duplicate.path("jobId").isNull()).isTrue();
        assertThat(jobRepository.count()).isZero();
    }

    @Test
    void failedLegacyScanWithoutJobCanBeQueuedAndProcessed() throws Exception {
        UUID assetId = createAsset("legacy-failed");
        byte[] report = readReport();
        UUID scanId = insertLegacyScan(assetId, report, "FAILED");

        JsonNode accepted = upload(assetId, report, "legacy-failed.json", 202);

        assertThat(accepted.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.path("scanId").asText()).isEqualTo(scanId.toString());
        assertThat(jobRepository.count()).isOne();
        IngestionJob job = jobRepository.findByScanId(scanId).orElseThrow();
        assertThat(reportStorage.exists(job.getPayloadKey())).isTrue();
        assertThat(worker.pollOnce()).isOne();
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.COMPLETED);
    }

    @Test
    void activeLegacyScansWithoutJobsCanBeRequeuedWithoutServerError() throws Exception {
        byte[] report = readReport();
        UUID receivedAssetId = createAsset("legacy-received");
        UUID processingAssetId = createAsset("legacy-processing");
        UUID receivedScanId = insertLegacyScan(receivedAssetId, report, "RECEIVED");
        UUID processingScanId = insertLegacyScan(processingAssetId, report, "PROCESSING");

        JsonNode received = upload(receivedAssetId, report, "received.json", 202);
        JsonNode processing = upload(processingAssetId, report, "processing.json", 202);

        assertThat(received.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(processing.path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(jobRepository.findByScanId(receivedScanId)).isPresent();
        assertThat(jobRepository.findByScanId(processingScanId)).isPresent();
        assertThat(scanRepository.findById(receivedScanId).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.RECEIVED);
        assertThat(scanRepository.findById(processingScanId).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.RECEIVED);
    }

    @Test
    void invalidReportGoesDirectlyToDeadLetter() throws Exception {
        JsonNode accepted = upload(createAsset("invalid"), "{}".getBytes(StandardCharsets.UTF_8), "bad.json", 202);

        worker.pollOnce();

        IngestionJob job = jobRepository.findById(UUID.fromString(accepted.path("jobId").asText())).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.DEAD_LETTER);
        assertThat(job.getAttemptCount()).isOne();
        assertThat(job.getLastError()).isEqualTo("Report validation failed");
        assertThat(scanRepository.findById(UUID.fromString(accepted.path("scanId").asText())).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.FAILED);
        assertThat(findingRepository.count()).isZero();

        JsonNode repeated = upload(
                UUID.fromString(accepted.path("assetId").asText()),
                "{}".getBytes(StandardCharsets.UTF_8),
                "bad-again.json",
                200);
        assertThat(repeated.path("outcome").asText()).isEqualTo("DEAD_LETTER");
        assertThat(jobRepository.count()).isOne();
    }

    @Test
    void transientFailureUsesBackoffAndCanCompleteOnRetry() throws Exception {
        JsonNode accepted = upload(createAsset("retry"), readReport(), "retry.json", 202);
        UUID jobId = UUID.fromString(accepted.path("jobId").asText());
        String payloadKey = jobRepository.findById(jobId).orElseThrow().getPayloadKey();
        doThrow(new TransientReportStorageException("temporary", new IOException("temporary")))
                .doCallRealMethod()
                .when(reportStorage).load(payloadKey);
        Instant before = Instant.now();

        worker.pollOnce();

        IngestionJob waiting = jobRepository.findById(jobId).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(IngestionJobStatus.RETRY_WAIT);
        assertThat(waiting.getAvailableAt()).isAfterOrEqualTo(before.plusSeconds(4));
        assertThat(waiting.getLastError()).isEqualTo("Temporary report storage failure");
        jdbcTemplate.update("UPDATE ingestion_jobs SET available_at = now() - interval '1 second' WHERE id = ?", jobId);

        worker.pollOnce();

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.COMPLETED);
    }

    @Test
    void exhaustedTransientFailuresBecomeDeadLetter() throws Exception {
        JsonNode accepted = upload(createAsset("exhausted"), readReport(), "retry.json", 202);
        UUID jobId = UUID.fromString(accepted.path("jobId").asText());
        doThrow(new TransientReportStorageException("temporary", new IOException("temporary")))
                .when(reportStorage).load(any());

        for (int attempt = 1; attempt <= 3; attempt++) {
            worker.pollOnce();
            if (attempt < 3) {
                jdbcTemplate.update(
                        "UPDATE ingestion_jobs SET available_at = now() - interval '1 second' WHERE id = ?",
                        jobId);
            }
        }

        IngestionJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getAttemptCount()).isEqualTo(3);
        assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.DEAD_LETTER);
        assertThat(scanRepository.findById(UUID.fromString(accepted.path("scanId").asText())).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.FAILED);
    }

    @Test
    void missingPayloadDoesNotLeaveAJobProcessing() throws Exception {
        JsonNode accepted = upload(createAsset("missing"), readReport(), "missing.json", 202);
        UUID jobId = UUID.fromString(accepted.path("jobId").asText());
        IngestionJob job = jobRepository.findById(jobId).orElseThrow();
        reportStorage.delete(job.getPayloadKey());

        worker.pollOnce();

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.DEAD_LETTER);
    }

    @Test
    void redriveIsExplicitStateCheckedAndConcurrentSafe() throws Exception {
        JsonNode pending = upload(createAsset("wrong-redrive"), readReport(), "pending.json", 202);
        mockMvc.perform(post("/api/v1/ingestion-jobs/{id}/redrive", pending.path("jobId").asText())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isConflict());

        JsonNode accepted = upload(createAsset("redrive"), "{}".getBytes(StandardCharsets.UTF_8), "bad.json", 202);
        UUID jobId = UUID.fromString(accepted.path("jobId").asText());
        worker.pollOnce();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> redriveStatus(jobId, start));
            Future<Integer> second = executor.submit(() -> redriveStatus(jobId, start));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(202, 409);
        } finally {
            executor.shutdownNow();
        }
        IngestionJob redriven = jobRepository.findById(jobId).orElseThrow();
        assertThat(redriven.getStatus()).isEqualTo(IngestionJobStatus.PENDING);
        assertThat(redriven.getAttemptCount()).isZero();
        assertThat(scanRepository.findById(UUID.fromString(accepted.path("scanId").asText())).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.RECEIVED);
        assertThat(jobRepository.count()).isEqualTo(2);
    }

    @Test
    void claimTokenRejectsAnOldWorkerAfterRedriveEvenWhenAttemptNumbersMatch() throws Exception {
        byte[] reportBytes = readReport();
        JsonNode accepted = upload(createAsset("claim-token-aba"), reportBytes, "aba.json", 202);
        UUID jobId = UUID.fromString(accepted.path("jobId").asText());
        JobClaim workerA = claimService.claimAvailable(1).get(0);
        jdbcTemplate.update("""
                UPDATE ingestion_jobs
                SET attempt_count = max_attempts,
                    locked_at = now() - interval '1 hour'
                WHERE id = ?
                """, jobId);

        assertThat(recoveryService.recoverStaleJobs()).isEqualTo(new RecoveryResult(0, 1));
        assertThat(jobRepository.findById(jobId).orElseThrow().getClaimToken()).isNull();
        redriveService.redrive(jobId);
        JobClaim workerB = claimService.claimAvailable(1).get(0);
        ParsedVulnerabilityReport report = reportParser.parse(reportBytes);

        assertThat(workerA.attempt()).isEqualTo(workerB.attempt());
        assertThat(workerA.claimToken()).isNotEqualTo(workerB.claimToken());
        assertThatThrownBy(() -> persistenceService.complete(jobId, workerA.claimToken(), report))
                .isInstanceOf(StaleJobClaimException.class);
        assertThat(failureService.handleFailure(
                jobId, workerA.claimToken(), true, "obsolete failure"))
                .isEqualTo(FailureDisposition.IGNORED_STALE_CLAIM);

        persistenceService.complete(jobId, workerB.claimToken(), report);

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.COMPLETED);
        assertThat(scanRepository.findById(workerB.scanId()).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.COMPLETED);
    }

    @Test
    void twoWorkersCannotClaimTheSameJob() throws Exception {
        upload(createAsset("one-claim"), readReport(), "one.json", 202);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<JobClaim>> first = executor.submit(() -> claimAfter(start));
            Future<List<JobClaim>> second = executor.submit(() -> claimAfter(start));
            start.countDown();
            int claimed = first.get(10, TimeUnit.SECONDS).size() + second.get(10, TimeUnit.SECONDS).size();
            assertThat(claimed).isOne();
        } finally {
            executor.shutdownNow();
        }
        assertThat(jobRepository.countByStatus(IngestionJobStatus.PROCESSING)).isOne();
    }

    @Test
    void skipLockedAllowsAnotherWorkerToClaimADifferentJob() throws Exception {
        JsonNode firstJob = upload(createAsset("skip-one"), readReport(), "one.json", 202);
        upload(createAsset("skip-two"), readReport(), "two.json", 202);
        UUID firstJobId = UUID.fromString(firstJob.path("jobId").asText());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> lockHolder = executor.submit(() -> template.execute(status -> {
                UUID id = jobRepository.findClaimableIds(Instant.now(), 1).get(0);
                locked.countDown();
                await(release);
                return id;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<List<JobClaim>> claimant = executor.submit(() -> claimService.claimAvailable(1));
            List<JobClaim> claimed = claimant.get(5, TimeUnit.SECONDS);
            release.countDown();
            UUID lockedId = lockHolder.get(5, TimeUnit.SECONDS);

            assertThat(claimed).singleElement().extracting(JobClaim::jobId).isNotEqualTo(lockedId);
            assertThat(List.of(lockedId, claimed.get(0).jobId())).contains(firstJobId);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void processingDoesNotHoldTheClaimLockWhileParsing() throws Exception {
        JsonNode accepted = upload(createAsset("lock-release"), readReport(), "lock.json", 202);
        UUID jobId = UUID.fromString(accepted.path("jobId").asText());
        CountDownLatch parsing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            parsing.countDown();
            await(release);
            return invocation.callRealMethod();
        }).when(reportParser).parse(any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> processing = executor.submit(worker::pollOnce);
            assertThat(parsing.await(5, TimeUnit.SECONDS)).isTrue();
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            Future<IngestionJobStatus> lockCheck = executor.submit(() -> template.execute(status ->
                    jobRepository.findByIdForUpdate(jobId).orElseThrow().getStatus()));
            assertThat(lockCheck.get(2, TimeUnit.SECONDS)).isEqualTo(IngestionJobStatus.PROCESSING);
            release.countDown();
            assertThat(processing.get(5, TimeUnit.SECONDS)).isOne();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void staleRecoveryRetriesOnceAndIsIdempotent() throws Exception {
        JsonNode accepted = upload(createAsset("recovery"), readReport(), "recovery.json", 202);
        UUID jobId = UUID.fromString(accepted.path("jobId").asText());
        assertThat(claimService.claimAvailable(1)).hasSize(1);
        jdbcTemplate.update(
                "UPDATE ingestion_jobs SET locked_at = now() - interval '1 hour' WHERE id = ?",
                jobId);

        RecoveryResult first = recoveryService.recoverStaleJobs();
        RecoveryResult second = recoveryService.recoverStaleJobs();

        assertThat(first).isEqualTo(new RecoveryResult(1, 0));
        assertThat(second).isEqualTo(RecoveryResult.none());
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.RETRY_WAIT);
    }

    @Test
    void concurrentRecoveriesRecoverAStaleJobOnlyOnce() throws Exception {
        JsonNode accepted = upload(createAsset("concurrent-recovery"), readReport(), "recovery.json", 202);
        UUID jobId = UUID.fromString(accepted.path("jobId").asText());
        assertThat(claimService.claimAvailable(1)).hasSize(1);
        jdbcTemplate.update(
                "UPDATE ingestion_jobs SET locked_at = now() - interval '1 hour' WHERE id = ?",
                jobId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RecoveryResult> first = executor.submit(() -> recoverAfter(start));
            Future<RecoveryResult> second = executor.submit(() -> recoverAfter(start));
            start.countDown();
            List<RecoveryResult> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));

            assertThat(results.stream().mapToInt(RecoveryResult::retried).sum()).isOne();
            assertThat(results.stream().mapToInt(RecoveryResult::deadLettered).sum()).isZero();
        } finally {
            executor.shutdownNow();
        }
        IngestionJob recovered = jobRepository.findById(jobId).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(IngestionJobStatus.RETRY_WAIT);
        assertThat(recovered.getClaimToken()).isNull();
    }

    @Test
    void staleRecoveryDeadLettersAJobWithoutAttempts() throws Exception {
        JsonNode accepted = upload(createAsset("recovery-dead"), readReport(), "recovery.json", 202);
        UUID jobId = UUID.fromString(accepted.path("jobId").asText());
        assertThat(claimService.claimAvailable(1)).hasSize(1);
        jdbcTemplate.update("""
                UPDATE ingestion_jobs
                SET attempt_count = max_attempts,
                    locked_at = now() - interval '1 hour'
                WHERE id = ?
                """, jobId);

        RecoveryResult result = recoveryService.recoverStaleJobs();

        assertThat(result).isEqualTo(new RecoveryResult(0, 1));
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.DEAD_LETTER);
    }

    @Test
    void pendingJobAndPayloadSurviveStorageReinitializationAndIgnoreClientPath() throws Exception {
        byte[] report = readReport();
        JsonNode accepted = upload(createAsset("persistent"), report, "../../client-name.json", 202);
        IngestionJob job = jobRepository.findById(UUID.fromString(accepted.path("jobId").asText())).orElseThrow();

        LocalFileReportStorage restarted =
                new LocalFileReportStorage(new ReportStorageProperties(REPORT_DIRECTORY));
        restarted.initialize();

        assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.PENDING);
        assertThat(job.getPayloadKey()).doesNotContain("client-name").doesNotContain("..");
        assertThat(restarted.load(job.getPayloadKey())).isEqualTo(report);
    }

    @Test
    void failedStorageWriteRollsBackScanAndJob() throws Exception {
        doThrow(new ReportStorageException("write failed", new IOException("write failed")))
                .when(reportStorage).store(any(), any());

        upload(createAsset("storage-failure"), readReport(), "failure.json", 500);

        assertThat(scanRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
    }

    @Test
    void databaseConstraintFailureRollsBackFindingReplacementAndCompletion() throws Exception {
        JsonNode accepted = upload(createAsset("atomic"), readReport(), "atomic.json", 202);
        UUID jobId = UUID.fromString(accepted.path("jobId").asText());
        UUID scanId = UUID.fromString(accepted.path("scanId").asText());
        UUID assetId = UUID.fromString(accepted.path("assetId").asText());
        Finding previous = findingRepository.saveAndFlush(new Finding(
                scanRepository.findById(scanId).orElseThrow(),
                assetRepository.findById(assetId).orElseThrow(),
                "CVE-PREVIOUS",
                "previous-package",
                "1.0",
                null,
                FindingSeverity.LOW,
                "Previous finding",
                null,
                false,
                20));
        ParsedVulnerabilityReport invalidPersistenceReport = new ParsedVulnerabilityReport(
                "test",
                List.of(
                        new ParsedVulnerability(
                                "CVE-VALID", "valid-package", "1", null,
                                FindingSeverity.HIGH, null, null),
                        new ParsedVulnerability(
                                "CVE-INVALID", null, "1", null,
                                FindingSeverity.HIGH, null, null)));
        doReturn(invalidPersistenceReport).when(reportParser).parse(any());

        worker.pollOnce();

        assertThat(findingRepository.findAll())
                .singleElement()
                .extracting(Finding::getId)
                .isEqualTo(previous.getId());
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(IngestionJobStatus.DEAD_LETTER);
        assertThat(scanRepository.findById(scanId).orElseThrow().getStatus())
                .isEqualTo(ScanStatus.FAILED);
    }

    @Test
    void unknownRuntimeFailureIsPermanentAndIsNotRetried() throws Exception {
        JsonNode accepted = upload(createAsset("unknown-failure"), readReport(), "unknown.json", 202);
        UUID jobId = UUID.fromString(accepted.path("jobId").asText());
        doThrow(new IllegalStateException("deterministic bug")).when(reportParser).parse(any());

        worker.pollOnce();

        IngestionJob failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(IngestionJobStatus.DEAD_LETTER);
        assertThat(failed.getAttemptCount()).isOne();
        assertThat(failed.getLastError()).isEqualTo("Permanent processing failure");
    }

    @Test
    void jobEndpointsRequireApiKeyAndOperationalEndpointsRemainPublic() throws Exception {
        mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(new MockMultipartFile(
                                "file", "report.json", "application/json", readReport()))
                        .param("assetId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/ingestion-jobs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/ingestion-jobs/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/ingestion-jobs/{id}/redrive", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }

    private JsonNode upload(UUID assetId, byte[] content, String fileName, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(new MockMultipartFile("file", fileName, "application/json", content))
                        .param("assetId", assetId.toString())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int redriveStatus(UUID jobId, CountDownLatch start) throws Exception {
        start.await();
        return mockMvc.perform(post("/api/v1/ingestion-jobs/{id}/redrive", jobId)
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andReturn().getResponse().getStatus();
    }

    private List<JobClaim> claimAfter(CountDownLatch start) throws Exception {
        start.await();
        return claimService.claimAvailable(1);
    }

    private RecoveryResult recoverAfter(CountDownLatch start) throws Exception {
        start.await();
        return recoveryService.recoverStaleJobs();
    }

    private UUID insertLegacyScan(UUID assetId, byte[] report, String status) throws Exception {
        UUID scanId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant startedAt = status.equals("PROCESSING") ? now : null;
        Instant completedAt = status.equals("COMPLETED") || status.equals("FAILED") ? now : null;
        String failureReason = status.equals("FAILED") ? "Legacy failure" : null;
        jdbcTemplate.update("""
                INSERT INTO scans (
                    id, asset_id, scanner, status, started_at, completed_at,
                    received_at, source_file_name, content_hash, failure_reason
                )
                VALUES (?, ?, 'TRIVY', ?, ?, ?, ?, 'legacy-report.json', ?, ?)
                """,
                scanId,
                assetId,
                status,
                startedAt == null ? null : startedAt.atOffset(ZoneOffset.UTC),
                completedAt == null ? null : completedAt.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC),
                sha256(report),
                failureReason);
        return scanId;
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private UUID createAsset(String name) {
        return assetRepository.save(new Asset(name, AssetType.CONTAINER_IMAGE, name + ":1")).getId();
    }

    private byte[] readReport() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/trivy-report.json")) {
            if (stream == null) {
                throw new IllegalStateException("Test report resource was not found");
            }
            return stream.readAllBytes();
        }
    }

    private static Path createReportDirectory() {
        try {
            return Files.createTempDirectory("vulnflow-it-reports-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test coordination was interrupted", exception);
        }
    }
}
