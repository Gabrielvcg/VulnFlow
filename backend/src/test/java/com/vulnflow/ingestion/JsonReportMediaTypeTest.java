package com.vulnflow.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vulnflow.asset.*;
import com.vulnflow.scan.ScanStatus;
import com.vulnflow.shared.error.GlobalExceptionHandler;
import com.vulnflow.shared.exception.*;
import com.vulnflow.ui.scan.UiScanRequestService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;

class JsonReportMediaTypeTest {
    private final AssetService assets = mock(AssetService.class);
    private final ScanSubmissionService submissions = mock(ScanSubmissionService.class);
    private final IngestionMetrics metrics = mock(IngestionMetrics.class);
    private final Asset asset = new Asset("demo", AssetType.HOST, "demo-host");
    private final byte[] content = "{\"Results\":[]}".getBytes(StandardCharsets.UTF_8);
    private final DefaultScanIngestionService service = new DefaultScanIngestionService(
            assets, submissions, new IngestionProperties(DataSize.ofBytes(100), 8000), metrics);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        when(assets.requireAsset(asset.getId())).thenReturn(asset);
        mvc = MockMvcBuilders.standaloneSetup(new ScanIngestionController(
                service, mock(UiScanRequestService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "APPLICATION/JSON", "application/vnd.demo+json",
            "text/vnd.demo+json", "application/*+json", "application/json; charset=UTF-8",
            "APPLICATION/JSON; CHARSET=\"UTF-8\"",
            "application/vnd.demo+json; profile=\"https://example.invalid/schema;v=1\""})
    void acceptsSupportedTypesAndPreservesBytesHashAndHttpContract(String type) throws Exception {
        UUID scanId = UUID.randomUUID();
        when(submissions.registerReceived(eq(asset), eq("report.json"), anyString(), eq(content)))
                .thenReturn(new IngestionSubmission(scanId, UUID.randomUUID(), asset.getId(),
                        ScanStatus.RECEIVED, IngestionJobStatus.PENDING, ScanIngestionOutcome.ACCEPTED));
        mvc.perform(multipart("/api/v1/scans/trivy").file(file(type, content))
                        .param("assetId", asset.getId().toString()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.scanId").value(scanId.toString()));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        verify(submissions).registerReceived(asset, "report.json", hash, content);
        verify(metrics).jobAccepted();
        verifyNoMoreInteractions(submissions, metrics);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "text/plain", "text/json", "application/xml", "application",
            "application/json; charset=", "application/json; charset=not-a-real-charset",
            "invalid+json", "application/j son+json"})
    void rejectsUnsupportedOrMalformedBeforeReadingOrRegistration(String type) throws Exception {
        MockMultipartFile upload = spy(file(type, content));
        assertThatThrownBy(() -> service.ingestTrivy(asset.getId(), upload))
                .isInstanceOf(UnsupportedReportMediaTypeException.class)
                .hasMessage("Only JSON report files are accepted");
        verify(upload, never()).getBytes();
        mvc.perform(multipart("/api/v1/scans/trivy").file(file(type, content))
                        .param("assetId", asset.getId().toString()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_REPORT_MEDIA_TYPE"));
        verifyNoInteractions(submissions, metrics);
    }

    @Test
    void preservesSizeAndEmptyGuardsBeforeReading() throws Exception {
        MockMultipartFile oversized = spy(file("application/json; charset=UTF-8", new byte[101]));
        assertThatThrownBy(() -> service.ingestTrivy(asset.getId(), oversized))
                .isInstanceOf(ReportTooLargeException.class);
        verify(oversized, never()).getBytes();
        assertThatThrownBy(() -> service.ingestTrivy(asset.getId(), file("application/json", new byte[0])))
                .isInstanceOf(InvalidReportException.class);
        assertThatThrownBy(() -> service.ingestTrivy(asset.getId(), null))
                .isInstanceOf(InvalidReportException.class);
        verifyNoInteractions(submissions, metrics);
    }

    private MockMultipartFile file(String type, byte[] bytes) {
        return new MockMultipartFile("file", "report.json", type, bytes);
    }
}
