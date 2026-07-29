package com.vulnflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScanStatus;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PostgreSQLFlowIT {

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

    @BeforeEach
    void cleanDatabase() {
        findingRepository.deleteAll();
        scanRepository.deleteAll();
        assetRepository.deleteAll();
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
    void executesTheCompleteIngestionAndDeduplicationFlow() throws Exception {
        String assetResponse = mockMvc.perform(post("/api/v1/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "integration-container",
                                  "type": "CONTAINER_IMAGE",
                                  "externalReference": "integration-test:1.0.0"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Correlation-ID"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID assetId = UUID.fromString(objectMapper.readTree(assetResponse).path("id").asText());
        byte[] report = readReport();

        String firstResponse = ingest(assetId, report, false);
        JsonNode firstJson = objectMapper.readTree(firstResponse);
        String scanId = firstJson.path("scanId").asText();

        mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(new MockMultipartFile("file", "trivy-report.json", "application/json", report))
                        .param("assetId", assetId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanId").value(scanId))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.findingsImported").value(3));

        mockMvc.perform(get("/api/v1/findings")
                        .param("assetId", assetId.toString())
                        .param("severity", "CRITICAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].riskScore").value(90));

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAssets").value(1))
                .andExpect(jsonPath("$.totalScans").value(1))
                .andExpect(jsonPath("$.totalFindings").value(3))
                .andExpect(jsonPath("$.criticalFindings").value(1))
                .andExpect(jsonPath("$.highFindings").value(1));
    }

    @Test
    void rejectsInvalidAssetInputWithAConsistentError() throws Exception {
        mockMvc.perform(post("/api/v1/assets")
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
                        .file(new MockMultipartFile(
                                "file",
                                "invalid.json",
                                "application/json",
                                invalidReport))
                        .param("assetId", asset.getId().toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REPORT"));

        assertThat(scanRepository.findAll())
                .singleElement()
                .extracting(scan -> scan.getStatus())
                .isEqualTo(ScanStatus.FAILED);
        assertThat(findingRepository.count()).isZero();
    }

    private String ingest(UUID assetId, byte[] report, boolean duplicate) throws Exception {
        return mockMvc.perform(multipart("/api/v1/scans/trivy")
                        .file(new MockMultipartFile("file", "trivy-report.json", "application/json", report))
                        .param("assetId", assetId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.findingsImported").value(3))
                .andExpect(jsonPath("$.criticalFindings").value(1))
                .andExpect(jsonPath("$.highFindings").value(1))
                .andExpect(jsonPath("$.duplicate").value(duplicate))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private byte[] readReport() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/trivy-report.json")) {
            if (stream == null) {
                throw new IllegalStateException("Test report resource was not found");
            }
            return stream.readAllBytes();
        }
    }
}
