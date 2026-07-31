package com.vulnflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetService;
import com.vulnflow.asset.AssetType;
import com.vulnflow.scan.ScanStatus;
import com.vulnflow.shared.exception.UnsupportedReportMediaTypeException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class DefaultScanIngestionServiceTest {

    private final AssetService assetService = mock(AssetService.class);
    private final ScanRegistrationService registrationService = mock(ScanRegistrationService.class);
    private final IngestionMetrics metrics = mock(IngestionMetrics.class);
    private final DefaultScanIngestionService service = new DefaultScanIngestionService(
            assetService,
            registrationService,
            new IngestionProperties(DataSize.ofMegabytes(10), 8000),
            metrics);

    private Asset asset;

    @BeforeEach
    void setUp() {
        asset = new Asset("demo", AssetType.HOST, "demo-host");
        when(assetService.requireAsset(asset.getId())).thenReturn(asset);
    }

    @Test
    void acceptsAndQueuesWithoutParsingTheReport() {
        byte[] content = "{\"Results\":[]}".getBytes(StandardCharsets.UTF_8);
        UUID scanId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(registrationService.registerReceived(eq(asset), eq("report.json"), any(), eq(content)))
                .thenReturn(new IngestionSubmission(
                        scanId,
                        jobId,
                        asset.getId(),
                        ScanStatus.RECEIVED,
                        IngestionJobStatus.PENDING,
                        ScanIngestionOutcome.ACCEPTED));

        ScanIngestionResponse response = service.ingestTrivy(
                asset.getId(),
                new MockMultipartFile("file", "report.json", "application/json", content));

        assertThat(response.scanId()).isEqualTo(scanId);
        assertThat(response.jobId()).isEqualTo(jobId);
        assertThat(response.scanStatus()).isEqualTo(ScanStatus.RECEIVED);
        assertThat(response.jobStatus()).isEqualTo(IngestionJobStatus.PENDING);
        assertThat(response.outcome()).isEqualTo(ScanIngestionOutcome.ACCEPTED);
        verify(metrics).jobAccepted();
    }

    @Test
    void keepsTheClientFileNameAsSanitizedMetadataOnly() {
        byte[] content = "{\"Results\":[]}".getBytes(StandardCharsets.UTF_8);
        when(registrationService.registerReceived(eq(asset), eq("report.json"), any(), eq(content)))
                .thenReturn(new IngestionSubmission(
                        UUID.randomUUID(), UUID.randomUUID(), asset.getId(),
                        ScanStatus.RECEIVED, IngestionJobStatus.PENDING, ScanIngestionOutcome.ACCEPTED));

        service.ingestTrivy(
                asset.getId(),
                new MockMultipartFile("file", "../../report.json", "application/json", content));

        verify(registrationService).registerReceived(eq(asset), eq("report.json"), any(), eq(content));
    }

    @Test
    void rejectsNonJsonMediaTypeBeforeRegistration() {
        MockMultipartFile file =
                new MockMultipartFile("file", "report.txt", "text/plain", "{}".getBytes());

        assertThatThrownBy(() -> service.ingestTrivy(asset.getId(), file))
                .isInstanceOf(UnsupportedReportMediaTypeException.class);
    }
}
