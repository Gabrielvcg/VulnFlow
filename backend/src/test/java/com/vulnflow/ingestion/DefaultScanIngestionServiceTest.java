package com.vulnflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetService;
import com.vulnflow.asset.AssetType;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.FindingSeverity;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScannerType;
import com.vulnflow.shared.exception.UnsupportedReportMediaTypeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class DefaultScanIngestionServiceTest {

    private final AssetService assetService = mock(AssetService.class);
    private final ScanRepository scanRepository = mock(ScanRepository.class);
    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final VulnerabilityReportParser parser = mock(VulnerabilityReportParser.class);
    private final IngestionPersistenceService persistenceService = mock(IngestionPersistenceService.class);
    private final DefaultScanIngestionService service = new DefaultScanIngestionService(
            assetService,
            scanRepository,
            findingRepository,
            parser,
            persistenceService,
            new IngestionProperties(DataSize.ofMegabytes(10)));

    private Asset asset;

    @BeforeEach
    void setUp() {
        asset = new Asset("demo", AssetType.HOST, "demo-host");
        when(assetService.requireAsset(asset.getId())).thenReturn(asset);
    }

    @Test
    void returnsExistingScanForDuplicateHashWithoutParsingAgain() throws Exception {
        byte[] content = "{\"Results\":[]}".getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        Scan existing = new Scan(asset, ScannerType.TRIVY, "report.json", hash);
        existing.markProcessing();
        existing.markCompleted("0.65.0");
        when(scanRepository.findByAssetIdAndContentHash(asset.getId(), hash))
                .thenReturn(Optional.of(existing));
        when(findingRepository.countByScanId(existing.getId())).thenReturn(3L);
        when(findingRepository.countByScanIdAndSeverity(existing.getId(), FindingSeverity.CRITICAL))
                .thenReturn(1L);
        when(findingRepository.countByScanIdAndSeverity(existing.getId(), FindingSeverity.HIGH))
                .thenReturn(1L);
        MockMultipartFile file =
                new MockMultipartFile("file", "report.json", "application/json", content);

        ScanIngestionResponse response = service.ingestTrivy(asset.getId(), file);

        assertThat(response.duplicate()).isTrue();
        assertThat(response.findingsImported()).isEqualTo(3);
        assertThat(response.criticalFindings()).isEqualTo(1);
        verify(parser, never()).parse(content);
        verify(persistenceService, never()).complete(existing, null);
    }

    @Test
    void rejectsNonJsonMediaType() {
        MockMultipartFile file =
                new MockMultipartFile("file", "report.txt", "text/plain", "{}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.ingestTrivy(asset.getId(), file))
                .isInstanceOf(UnsupportedReportMediaTypeException.class);
    }
}

