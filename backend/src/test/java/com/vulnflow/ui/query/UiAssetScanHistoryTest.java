package com.vulnflow.ui.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetRepository;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.processing.port.ProcessingResultReader;
import com.vulnflow.processing.port.ProcessingResultStatus;
import com.vulnflow.processing.port.ProcessingResultSummary;
import com.vulnflow.processing.port.TransientProcessingStoreException;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScanResponse;
import com.vulnflow.scan.ScanStatus;
import com.vulnflow.scan.ScannerType;
import com.vulnflow.shared.exception.ResourceNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class UiAssetScanHistoryTest {
    private static final UUID ASSET = new UUID(0, 1);
    private static final Instant RECEIVED = Instant.parse("2026-10-24T12:00:00Z");
    private static final Instant STARTED = RECEIVED.plusSeconds(1);
    private static final Instant FINISHED = RECEIVED.plusSeconds(2);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final ScanRepository scans = mock(ScanRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final ProcessingResultReader reader = mock(ProcessingResultReader.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ProcessingResultReader> readers = mock(ObjectProvider.class);
    private final UiQueryController controller = new UiQueryController(assets, scans, findings, null, null, null,
            null, null, null, null, readers, "");

    UiAssetScanHistoryTest() {
        when(assets.findById(ASSET)).thenReturn(Optional.of(mock(Asset.class)));
    }

    @ParameterizedTest
    @EnumSource(ProcessingResultStatus.class)
    void projectsRemoteStateAndCompletionFieldsWhilePreservingLocalIdentityAndReceipt(ProcessingResultStatus status) {
        Scan local = scan(1, ScanStatus.RECEIVED);
        var stored = summary(local, status);
        when(readers.getIfAvailable()).thenReturn(reader);
        when(scans.findByAssetIdOrderByReceivedAtDesc(ASSET, PageRequest.of(0, 25)))
                .thenReturn(new PageImpl<>(List.of(local), PageRequest.of(0, 25), 40));
        UUID id = local.getId();
        when(reader.findScans(List.of(id))).thenReturn(Map.of(id, stored));

        var actual = controller.assetScans(ASSET, 0, 25);

        assertThat(actual.getContent()).containsExactly(expected(local, status));
        assertThat(actual.getTotalElements()).isEqualTo(40);
        assertThat(actual.getTotalPages()).isEqualTo(2);
        verify(reader).findScans(List.of(local.getId()));
        verify(reader, never()).findScan(any());
        verify(scans, never()).save(any());
        verify(local, never()).markReceived();
        verify(local, never()).markProcessing();
        verify(local, never()).markCompleted(any());
        verify(local, never()).markFailed(any());
        verifyNoInteractions(findings);
        assertThat(local.getStatus()).isEqualTo(ScanStatus.RECEIVED);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void mixedPagesUseOneSummaryBatchForOnlyTheReturnedRows(int page) {
        Scan completed = scan(page * 10 + 1, ScanStatus.RECEIVED);
        Scan local = scan(page * 10 + 2, ScanStatus.COMPLETED);
        Scan pending = scan(page * 10 + 3, ScanStatus.RECEIVED);
        List<Scan> content = List.of(completed, local, pending);
        var pageable = PageRequest.of(page, 3);
        when(readers.getIfAvailable()).thenReturn(reader);
        when(scans.findByAssetIdOrderByReceivedAtDesc(ASSET, pageable)).thenReturn(new PageImpl<>(content, pageable, 10));
        List<UUID> ids = content.stream().map(Scan::getId).toList();
        Map<UUID, ProcessingResultSummary> summaries = Map.of(completed.getId(), summary(completed, ProcessingResultStatus.COMPLETED));
        when(reader.findScans(ids)).thenReturn(summaries);

        var actual = controller.assetScans(ASSET, page, 3);

        assertThat(actual.getContent()).containsExactly(expected(completed, ProcessingResultStatus.COMPLETED),
                ScanResponse.from(local), ScanResponse.from(pending));
        assertThat(actual.getPageable()).isEqualTo(pageable);
        assertThat(actual.getTotalElements()).isEqualTo(10);
        assertThat(actual.getNumber()).isEqualTo(page);
        assertThat(actual.getTotalPages()).isEqualTo(4);
        verify(scans).findByAssetIdOrderByReceivedAtDesc(ASSET, pageable);
        verify(reader).findScans(ids);
        verify(reader, never()).findScan(any());
        verifyNoInteractions(findings);
    }

    @Test
    void emptySubsequentPageRetainsItsTotalAndDoesNotReadRemoteResults() {
        when(readers.getIfAvailable()).thenReturn(reader);
        var pageable = PageRequest.of(5, 25);
        when(scans.findByAssetIdOrderByReceivedAtDesc(ASSET, pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 40));

        var actual = controller.assetScans(ASSET, 5, 25);

        assertThat(actual.getContent()).isEmpty();
        assertThat(actual.getTotalElements()).isEqualTo(40);
        assertThat(actual.getNumber()).isEqualTo(5);
        verifyNoInteractions(reader, findings);
    }

    @Test
    void localModeKeepsTheExistingCompleteDtoAndNormalizesPageBounds() {
        Scan local = scan(1, ScanStatus.FAILED);
        when(scans.findByAssetIdOrderByReceivedAtDesc(ASSET, PageRequest.of(0, 100)))
                .thenReturn(new PageImpl<>(List.of(local), PageRequest.of(0, 100), 1));

        var actual = controller.assetScans(ASSET, -1, 500);

        assertThat(actual.getContent()).containsExactly(ScanResponse.from(local));
        assertThat(actual.getPageable()).isEqualTo(PageRequest.of(0, 100));
        verifyNoInteractions(reader, findings);
    }

    @Test
    void aRemoteReaderFailureIsPropagatedRatherThanReportedAsLocalSuccess() {
        Scan local = scan(1, ScanStatus.RECEIVED);
        when(readers.getIfAvailable()).thenReturn(reader);
        when(scans.findByAssetIdOrderByReceivedAtDesc(ASSET, PageRequest.of(0, 25)))
                .thenReturn(new PageImpl<>(List.of(local)));
        TransientProcessingStoreException failure = new TransientProcessingStoreException("fixture outage", null);
        when(reader.findScans(List.of(local.getId()))).thenThrow(failure);

        assertThatThrownBy(() -> controller.assetScans(ASSET, 0, 25)).isSameAs(failure);
        verify(scans, never()).save(any());
    }

    @Test
    void unknownAssetDoesNotQueryHistoryOrRemoteResults() {
        when(assets.findById(ASSET)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.assetScans(ASSET, 0, 25)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(scans, readers, reader, findings);
    }

    @Test
    void aFullPageStillUsesOnlyOneReaderBatch() {
        List<Scan> content = new ArrayList<>();
        for (int id = 1; id <= 100; id++) content.add(scan(id, ScanStatus.RECEIVED));
        when(readers.getIfAvailable()).thenReturn(reader);
        when(scans.findByAssetIdOrderByReceivedAtDesc(ASSET, PageRequest.of(1, 100)))
                .thenReturn(new PageImpl<>(content, PageRequest.of(1, 100), 400));
        var ids = content.stream().map(Scan::getId).toList();
        when(reader.findScans(ids)).thenReturn(Map.of());

        var actual = controller.assetScans(ASSET, 1, 100);

        assertThat(actual.getContent()).containsExactlyElementsOf(content.stream().map(ScanResponse::from).toList());
        assertThat(actual.getTotalElements()).isEqualTo(400);
        verify(reader).findScans(ids);
        verify(reader, never()).findScan(any());
    }

    private static ScanResponse expected(Scan scan, ProcessingResultStatus status) {
        return new ScanResponse(scan.getId(), ASSET, ScannerType.TRIVY,
                status == ProcessingResultStatus.COMPLETED ? "remote-0.60.0" : null,
                switch (status) {
                    case RECEIVED, PUBLISH_PENDING, QUEUED -> ScanStatus.RECEIVED;
                    case PROCESSING -> ScanStatus.PROCESSING;
                    case COMPLETED -> ScanStatus.COMPLETED;
                    case FAILED -> ScanStatus.FAILED;
                }, STARTED,
                status == ProcessingResultStatus.COMPLETED || status == ProcessingResultStatus.FAILED ? FINISHED : null,
                RECEIVED, "local-report.json", "a".repeat(64), status == ProcessingResultStatus.FAILED ? "The report is invalid" : null);
    }

    private static ProcessingResultSummary summary(Scan scan, ProcessingResultStatus status) {
        return new ProcessingResultSummary(null, scan.getId(), ASSET, null, null, "TRIVY",
                status == ProcessingResultStatus.COMPLETED ? "remote-0.60.0" : null, status,
                RECEIVED.plusSeconds(30), status == ProcessingResultStatus.COMPLETED || status == ProcessingResultStatus.FAILED ? FINISHED : null,
                0, Map.of(), status == ProcessingResultStatus.FAILED ? "INVALID_REPORT" : null,
                status == ProcessingResultStatus.FAILED ? "The report is invalid" : "stale diagnostic must not appear as failureReason");
    }

    private static Scan scan(int id, ScanStatus status) {
        Scan scan = mock(Scan.class);
        Asset asset = mock(Asset.class);
        when(asset.getId()).thenReturn(ASSET);
        when(scan.getAsset()).thenReturn(asset);
        when(scan.getId()).thenReturn(new UUID(0, id));
        when(scan.getScanner()).thenReturn(ScannerType.TRIVY);
        when(scan.getScannerVersion()).thenReturn("local-old");
        when(scan.getStatus()).thenReturn(status);
        when(scan.getStartedAt()).thenReturn(STARTED);
        when(scan.getCompletedAt()).thenReturn(RECEIVED.minusSeconds(60));
        when(scan.getReceivedAt()).thenReturn(RECEIVED);
        when(scan.getSourceFileName()).thenReturn("local-report.json");
        when(scan.getContentHash()).thenReturn("a".repeat(64));
        when(scan.getFailureReason()).thenReturn("local previous reason");
        return scan;
    }
}
