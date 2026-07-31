package com.vulnflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetRepository;
import com.vulnflow.asset.AssetType;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.FindingRiskCalculator;
import com.vulnflow.ingestion.ScanFailureService;
import com.vulnflow.ingestion.ScanIngestionOutcome;
import com.vulnflow.ingestion.ScanRecoveryService;
import com.vulnflow.ingestion.ScanRegistration;
import com.vulnflow.ingestion.ScanRegistrationService;
import com.vulnflow.ingestion.VulnerabilityReportParser;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScanStatus;
import com.vulnflow.security.ApiKeyAuthenticationFilter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "vulnflow.security.api-key.value=test-api-key")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PostgreSQLFlowIT {

    private static final String API_KEY = "test-api-key";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    ScanRepository scanRepository;

    @Autowired
    AssetRepository assetRepository;

    @Autowired
    ScanRegistrationService registrationService;

    @Autowired
    ScanFailureService failureService;

    @Autowired
    ScanRecoveryService recoveryService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    VulnerabilityReportParser reportParser;

    @MockitoSpyBean
    FindingRiskCalculator riskCalculator;

    @BeforeEach
    void cleanDatabase() {
        reset(reportParser, riskCalculator);
        findingRepository.deleteAll();
        scanRepository.deleteAll();
        assetRepository.deleteAll();
    }

    @AfterEach
    void resetSpies() {
        reset(reportParser, riskCalculator);
    }

    @Test
    void persistsAnAssetInPostgreSql() {
        Asset saved = assetRepository.save(new Asset("database-test", AssetType.HOST, "host-01"));

        assertThat(assetRepository.findById(saved.getId()))
                .get()
                .extracting(Asset::getName)
                .isEqualTo("database-test");
    }

    @Test
    void executesTheCompleteIngestionAndCompletedDeduplicationFlow() throws Exception {
        UUID assetId = createAsset("integration-container", "integration-test:1.0.0");
        byte[] report = readReport();

        String firstResponse = ingest(assetId, report, "trivy-report.json", false);
        JsonNode firstJson = objectMapper.readTree(firstResponse);
        String scanId = firstJson.path("scanId").asText();

        mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(reportFile("same-content-different-name.json", report))
                        .param("assetId", assetId.toString())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanId").value(scanId))
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.findingsImported").value(0))
                .andExpect(jsonPath("$.totalFindings").value(3));

        assertThat(scanRepository.count()).isEqualTo(1);
        assertThat(findingRepository.count()).isEqualTo(3);
    }

    @Test
    void retriesTheSameScanAfterFailedAndReusesItsId() throws Exception {
        Asset asset = assetRepository.save(
                new Asset("retry-test", AssetType.CONTAINER_IMAGE, "retry:1"));
        byte[] report = readReport();
        String hash = sha256(report);
        ScanRegistration registration =
                registrationService.registerProcessing(asset, "first.json", hash);
        failureService.markFailed(registration.scanId(), "Simulated transient failure");

        mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(reportFile("retry.json", report))
                        .param("assetId", asset.getId().toString())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanId").value(registration.scanId().toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.outcome").value("RETRIED"))
                .andExpect(jsonPath("$.findingsImported").value(3))
                .andExpect(jsonPath("$.totalFindings").value(3));

        assertThat(scanRepository.count()).isEqualTo(1);
        assertThat(findingRepository.count()).isEqualTo(3);
    }

    @Test
    void returnsAcceptedWhenTheSameScanIsAlreadyProcessing() throws Exception {
        Asset asset = assetRepository.save(
                new Asset("processing-test", AssetType.CONTAINER_IMAGE, "processing:1"));
        byte[] report = readReport();
        ScanRegistration registration =
                registrationService.registerProcessing(asset, "processing.json", sha256(report));

        mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(reportFile("another-name.json", report))
                        .param("assetId", asset.getId().toString())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.scanId").value(registration.scanId().toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.outcome").value("ALREADY_PROCESSING"))
                .andExpect(jsonPath("$.findingsImported").value(0))
                .andExpect(jsonPath("$.duplicate").value(true));

        assertThat(scanRepository.count()).isEqualTo(1);
        assertThat(findingRepository.count()).isZero();
    }

    @Test
    void serializesTwoSimultaneousRequestsForTheSameAssetAndContent() throws Exception {
        UUID assetId = createAsset("concurrency-test", "concurrency:1");
        byte[] report = readReport();
        CountDownLatch parserEntered = new CountDownLatch(1);
        CountDownLatch releaseParser = new CountDownLatch(1);
        doAnswer(invocation -> {
            parserEntered.countDown();
            if (!releaseParser.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release parser");
            }
            return invocation.callRealMethod();
        }).when(reportParser).parse(any(byte[].class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> firstRequest = executor.submit(() -> mockMvc.perform(
                            multipart("/api/v1/scans/trivy")
                                    .file(reportFile("first.json", report))
                                    .param("assetId", assetId.toString())
                                    .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                    .andExpect(status().isOk())
                    .andReturn());
            assertThat(parserEntered.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> secondRequest = executor.submit(() -> mockMvc.perform(
                            multipart("/api/v1/scans/trivy")
                                    .file(reportFile("second.json", report))
                                    .param("assetId", assetId.toString())
                                    .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.outcome").value("ALREADY_PROCESSING"))
                    .andReturn());

            MvcResult secondResult = secondRequest.get(10, TimeUnit.SECONDS);
            releaseParser.countDown();
            MvcResult firstResult = firstRequest.get(10, TimeUnit.SECONDS);

            String firstScanId =
                    objectMapper.readTree(firstResult.getResponse().getContentAsString()).path("scanId").asText();
            String secondScanId =
                    objectMapper.readTree(secondResult.getResponse().getContentAsString()).path("scanId").asText();
            assertThat(secondScanId).isEqualTo(firstScanId);
        } finally {
            releaseParser.countDown();
            executor.shutdownNow();
        }

        assertThat(scanRepository.count()).isEqualTo(1);
        assertThat(findingRepository.count()).isEqualTo(3);
    }

    @Test
    void allowsTheSameContentForDifferentAssets() throws Exception {
        UUID firstAsset = createAsset("first-asset", "same:1");
        UUID secondAsset = createAsset("second-asset", "same:2");
        byte[] report = readReport();

        String first = ingest(firstAsset, report, "same.json", false);
        String second = ingest(secondAsset, report, "same.json", false);

        assertThat(objectMapper.readTree(first).path("scanId").asText())
                .isNotEqualTo(objectMapper.readTree(second).path("scanId").asText());
        assertThat(scanRepository.count()).isEqualTo(2);
        assertThat(findingRepository.count()).isEqualTo(6);
    }

    @Test
    void rollsBackAllFindingsWhenPersistenceFailsAndRecordsFailed() throws Exception {
        Asset asset = assetRepository.save(
                new Asset("rollback-test", AssetType.CONTAINER_IMAGE, "rollback:1"));
        AtomicInteger calculation = new AtomicInteger();
        doAnswer(invocation -> {
            if (calculation.incrementAndGet() == 2) {
                throw new IllegalStateException("Simulated persistence preparation failure");
            }
            return invocation.callRealMethod();
        }).when(riskCalculator).calculate(any(), any(Boolean.class));

        mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(reportFile("rollback.json", readReport()))
                        .param("assetId", asset.getId().toString())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(scanRepository.findAll())
                .singleElement()
                .satisfies(scan -> {
                    assertThat(scan.getStatus()).isEqualTo(ScanStatus.FAILED);
                    assertThat(scan.getFailureReason()).isEqualTo("Report processing failed");
                });
        assertThat(findingRepository.count()).isZero();
    }

    @Test
    void recoversOnlyStaleProcessingScansAndIsIdempotent() {
        Asset asset = assetRepository.save(
                new Asset("recovery-test", AssetType.APPLICATION, "recovery"));
        ScanRegistration stale =
                registrationService.registerProcessing(asset, "stale.json", "a".repeat(64));
        ScanRegistration recent =
                registrationService.registerProcessing(asset, "recent.json", "b".repeat(64));
        jdbcTemplate.update(
                "UPDATE scans SET started_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))),
                stale.scanId());

        assertThat(recoveryService.recoverStaleProcessingScans()).isEqualTo(1);
        assertThat(recoveryService.recoverStaleProcessingScans()).isZero();

        assertThat(scanRepository.findById(stale.scanId()))
                .get()
                .satisfies(scan -> {
                    assertThat(scan.getStatus()).isEqualTo(ScanStatus.FAILED);
                    assertThat(scan.getFailureReason()).isEqualTo("Processing timeout exceeded");
                });
        assertThat(scanRepository.findById(recent.scanId()))
                .get()
                .extracting(Scan::getStatus)
                .isEqualTo(ScanStatus.PROCESSING);
    }

    @Test
    void rejectsRequestsWithoutOrWithWrongApiKeyAndAcceptsTheCorrectKey() throws Exception {
        mockMvc.perform(get("/api/v1/assets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));

        mockMvc.perform(get("/api/v1/assets")
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));

        mockMvc.perform(get("/api/v1/assets")
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void keepsHealthAndSwaggerPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void mapsCommonClientErrorsToFourXxResponses() throws Exception {
        mockMvc.perform(post("/api/v1/assets")
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));

        mockMvc.perform(post("/api/v1/assets")
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        mockMvc.perform(post("/api/v1/assets")
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"invalid-enum\",\"type\":\"NOPE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));

        mockMvc.perform(get("/api/v1/assets/not-a-uuid")
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(reportFile("report.json", readReport()))
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .param("assetId", UUID.randomUUID().toString())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUEST_PART"));
    }

    @Test
    void mapsOversizedInvalidAndMissingAssetReportsCorrectly() throws Exception {
        Asset asset = assetRepository.save(
                new Asset("error-test", AssetType.APPLICATION, "errors"));
        byte[] oversized = new byte[(10 * 1024 * 1024) + 1];

        mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(reportFile("oversized.json", oversized))
                        .param("assetId", asset.getId().toString())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("REPORT_TOO_LARGE"));

        mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(reportFile("invalid.json", "{}".getBytes(StandardCharsets.UTF_8)))
                        .param("assetId", asset.getId().toString())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REPORT"));

        mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(reportFile("valid.json", readReport()))
                        .param("assetId", UUID.randomUUID().toString())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidAssetInputWithAConsistentError() throws Exception {
        mockMvc.perform(post("/api/v1/assets")
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": " ", "type": null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.type").exists());
    }

    @Test
    void recordsAFailedScanWhenTheReportIsMalformed() throws Exception {
        Asset asset = assetRepository.save(
                new Asset("invalid-report-test", AssetType.APPLICATION, "invalid-report"));
        byte[] invalidReport = "{\"Results\": [".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(reportFile("invalid.json", invalidReport))
                        .param("assetId", asset.getId().toString())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REPORT"));

        assertThat(scanRepository.findAll())
                .singleElement()
                .extracting(Scan::getStatus)
                .isEqualTo(ScanStatus.FAILED);
        assertThat(findingRepository.count()).isZero();
    }

    @Test
    void omitsDescriptionFromListButKeepsItInDetail() throws Exception {
        UUID assetId = createAsset("description-test", "description:1");
        ingest(assetId, readReport(), "description.json", false);

        String findingsResponse = mockMvc.perform(get("/api/v1/findings")
                        .param("assetId", assetId.toString())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].description").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String findingId = objectMapper.readTree(findingsResponse)
                .path("content")
                .path(0)
                .path("id")
                .asText();

        mockMvc.perform(get("/api/v1/findings/{id}", findingId)
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").hasJsonPath());
    }

    private UUID createAsset(String name, String externalReference) throws Exception {
        String assetResponse = mockMvc.perform(post("/api/v1/assets")
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "type": "CONTAINER_IMAGE",
                                  "externalReference": "%s"
                                }
                                """.formatted(name, externalReference)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Correlation-ID"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(assetResponse).path("id").asText());
    }

    private String ingest(
            UUID assetId,
            byte[] report,
            String fileName,
            boolean duplicate) throws Exception {
        return mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(reportFile(fileName, report))
                        .param("assetId", assetId.toString())
                        .header(ApiKeyAuthenticationFilter.HEADER_NAME, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.findingsImported").value(duplicate ? 0 : 3))
                .andExpect(jsonPath("$.totalFindings").value(3))
                .andExpect(jsonPath("$.criticalFindings").value(1))
                .andExpect(jsonPath("$.highFindings").value(1))
                .andExpect(jsonPath("$.duplicate").value(duplicate))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private MockMultipartFile reportFile(String fileName, byte[] content) {
        return new MockMultipartFile("file", fileName, "application/json", content);
    }

    private byte[] readReport() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/trivy-report.json")) {
            if (stream == null) {
                throw new IllegalStateException("Test report resource was not found");
            }
            return stream.readAllBytes();
        }
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
