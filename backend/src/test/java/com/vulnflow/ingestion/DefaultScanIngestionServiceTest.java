package com.vulnflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetService;
import com.vulnflow.asset.AssetType;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.scan.ScanStatus;
import com.vulnflow.shared.exception.UnsupportedReportMediaTypeException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class DefaultScanIngestionServiceTest {

    private final AssetService assetService = mock(AssetService.class);
    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final VulnerabilityReportParser parser = mock(VulnerabilityReportParser.class);
    private final ScanRegistrationService registrationService = mock(ScanRegistrationService.class);
    private final IngestionPersistenceService persistenceService = mock(IngestionPersistenceService.class);
    private final ScanFailureService failureService = mock(ScanFailureService.class);
    private final DefaultScanIngestionService service = new DefaultScanIngestionService(
            assetService,
            findingRepository,
            parser,
            registrationService,
            persistenceService,
            failureService,
            new IngestionProperties(DataSize.ofMegabytes(10), Duration.ofMinutes(15), 8000));

    private Asset asset;

    @BeforeEach
    void setUp() {
        asset = new Asset("demo", AssetType.HOST, "demo-host");
        when(assetService.requireAsset(asset.getId())).thenReturn(asset);
    }

    @Test
    void returnsCompletedScanAsDuplicateWithoutParsingAgain() {
        byte[] content = "{\"Results\":[]}".getBytes();
        ScanRegistration registration = new ScanRegistration(
                java.util.UUID.randomUUID(),
                asset.getId(),
                ScanStatus.COMPLETED,
                ScanIngestionOutcome.DUPLICATE);
        when(registrationService.registerProcessing(eq(asset), eq("report.json"), any()))
                .thenReturn(registration);
        when(findingRepository.countByScanId(registration.scanId())).thenReturn(3L);
        MockMultipartFile file =
                new MockMultipartFile("file", "report.json", "application/json", content);

        ScanIngestionResponse response = service.ingestTrivy(asset.getId(), file);

        assertThat(response.duplicate()).isTrue();
        assertThat(response.findingsImported()).isZero();
        assertThat(response.totalFindings()).isEqualTo(3);
        assertThat(response.outcome()).isEqualTo(ScanIngestionOutcome.DUPLICATE);
        verifyNoInteractions(parser, persistenceService, failureService);
    }

    @Test
    void rejectsNonJsonMediaType() {
        MockMultipartFile file =
                new MockMultipartFile("file", "report.txt", "text/plain", "{}".getBytes());

        assertThatThrownBy(() -> service.ingestTrivy(asset.getId(), file))
                .isInstanceOf(UnsupportedReportMediaTypeException.class);
    }

    @Test
    void doesNotMarkCompletedScanAsFailedWhenResponseCountingFails() {
        byte[] content = "{\"Results\":[]}".getBytes();
        ScanRegistration registration = new ScanRegistration(
                java.util.UUID.randomUUID(),
                asset.getId(),
                ScanStatus.PROCESSING,
                ScanIngestionOutcome.IMPORTED);
        when(registrationService.registerProcessing(eq(asset), eq("report.json"), any()))
                .thenReturn(registration);
        when(parser.parse(content)).thenReturn(new ParsedVulnerabilityReport("1.0", List.of()));
        when(findingRepository.countByScanId(registration.scanId()))
                .thenThrow(new IllegalStateException("count failed"));
        MockMultipartFile file =
                new MockMultipartFile("file", "report.json", "application/json", content);

        assertThatThrownBy(() -> service.ingestTrivy(asset.getId(), file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("count failed");

        verify(persistenceService).complete(eq(registration.scanId()), any());
        verify(failureService, never()).markFailed(any(), any());
    }

    @Test
    void preservesOriginalExceptionWhenMarkingFailedAlsoFails() {
        byte[] content = "{\"Results\":[]}".getBytes();
        ScanRegistration registration = new ScanRegistration(
                java.util.UUID.randomUUID(),
                asset.getId(),
                ScanStatus.PROCESSING,
                ScanIngestionOutcome.IMPORTED);
        IllegalArgumentException parseFailure = new IllegalArgumentException("parse failed");
        IllegalStateException markFailure = new IllegalStateException("mark failed");
        when(registrationService.registerProcessing(eq(asset), eq("report.json"), any()))
                .thenReturn(registration);
        when(parser.parse(content)).thenThrow(parseFailure);
        doThrow(markFailure)
                .when(failureService)
                .markFailed(registration.scanId(), "Report processing failed");
        MockMultipartFile file =
                new MockMultipartFile("file", "report.json", "application/json", content);

        assertThatThrownBy(() -> service.ingestTrivy(asset.getId(), file))
                .isSameAs(parseFailure)
                .satisfies(exception -> assertThat(exception.getSuppressed()).containsExactly(markFailure));
    }
}
