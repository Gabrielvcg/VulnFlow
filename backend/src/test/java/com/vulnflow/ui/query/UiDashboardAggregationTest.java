package com.vulnflow.ui.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vulnflow.asset.Asset;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.FindingSeverity;
import com.vulnflow.processing.port.ProcessingResultReader;
import com.vulnflow.processing.port.ProcessingResultStatus;
import com.vulnflow.processing.port.ProcessingResultSummary;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.ui.UiProperties;
import com.vulnflow.ui.scan.UiAgent;
import com.vulnflow.ui.scan.UiAgentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class UiDashboardAggregationTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    static Stream<Arguments> selectedScanSets() {
        return Stream.of(1, 25, 100, 500).flatMap(count -> Stream.of("LOCAL", "AWS", "MIXED")
                .map(mode -> Arguments.of(count, mode)));
    }

    @ParameterizedTest(name = "scans={0}, mode={1}")
    @MethodSource("selectedScanSets")
    void preservesTheCompleteDashboardWithinTheBulkFallbackBudget(int count, String mode) {
        List<Scan> selected = new ArrayList<>();
        for (int index = 1; index <= count; index++) selected.add(scan(index, index));
        verifyDashboard(selected, selected, mode, false);
    }

    @Test
    void emptyWindowDoesNotQueryLatestScansOrAnyFindings() {
        verifyDashboard(List.of(), List.of(), "LOCAL", false);
    }

    @Test
    void retainsBothDistinctLatestScanIdsWhenAnAssetHasATimestampTie() {
        // The repository's MAX(receivedAt) predicate selects both distinct scan rows on a tie.
        verifyDashboard(List.of(scan(9, 1)), List.of(scan(1, 1), scan(2, 1)), "LOCAL", false);
    }

    @Test
    void aggregatesBeyondIntegerRangeUsingLongCountsIncludingUnknown() {
        verifyDashboard(List.of(scan(1, 1), scan(2, 2)), List.of(scan(1, 1), scan(2, 2)), "MIXED", true);
    }

    @SuppressWarnings("unchecked")
    private void verifyDashboard(List<Scan> recent, List<Scan> selected, String mode, boolean largeCounts) {
        ScanRepository scans = mock(ScanRepository.class);
        FindingRepository findings = mock(FindingRepository.class);
        UiAgentRepository agents = mock(UiAgentRepository.class);
        ObjectProvider<ProcessingResultReader> provider = mock(ObjectProvider.class);
        ProcessingResultReader reader = mock(ProcessingResultReader.class);
        Map<UUID, ProcessingResultSummary> aws = new LinkedHashMap<>();
        Map<UUID, Map<FindingSeverity, Long>> local = new LinkedHashMap<>();
        Map<String, Long> expectedSeverity = new LinkedHashMap<>();
        for (FindingSeverity severity : FindingSeverity.values()) expectedSeverity.put(severity.name(), 0L);
        for (int index = 0; index < selected.size(); index++) {
            Scan scan = selected.get(index);
            boolean isAws = mode.equals("AWS") || mode.equals("MIXED") && index % 2 == 0;
            Map<FindingSeverity, Long> localCounts = new LinkedHashMap<>();
            Map<String, Integer> awsCounts = new LinkedHashMap<>();
            for (FindingSeverity severity : FindingSeverity.values()) {
                long value = (largeCounts ? Integer.MAX_VALUE : index + 1L) + severity.ordinal();
                // Missing AWS categories must keep the existing zero fallback, not query local counts.
                if (isAws) {
                    if (largeCounts || severity != FindingSeverity.MEDIUM) awsCounts.put(severity.name(),
                            largeCounts ? Integer.MAX_VALUE : (int) value);
                    value = awsCounts.getOrDefault(severity.name(), 0);
                } else localCounts.put(severity, value);
                expectedSeverity.compute(severity.name(), (key, total) -> total + valueForSum(isAws, awsCounts, localCounts, severity));
            }
            if (isAws) aws.put(scan.getId(), new ProcessingResultSummary(null, scan.getId(), scan.getAsset().getId(),
                    null, null, "TRIVY", null, ProcessingResultStatus.COMPLETED, NOW, NOW, 0, awsCounts, null, null));
            else local.put(scan.getId(), localCounts);
        }
        List<UUID> assetIds = recent.stream().map(scan -> scan.getAsset().getId()).distinct().toList();
        when(scans.findByReceivedAtAfterOrderByReceivedAtDesc(NOW.minusSeconds(30L * 86400), PageRequest.of(0, 500)))
                .thenReturn(new PageImpl<>(recent));
        if (!assetIds.isEmpty()) when(scans.findLatestByAssetIds(assetIds)).thenReturn(selected);
        if (!mode.equals("LOCAL")) {
            when(provider.getIfAvailable()).thenReturn(reader);
            if (!selected.isEmpty()) when(reader.findScans(selected.stream().map(Scan::getId).toList())).thenReturn(aws);
        }
        when(findings.countByScanIdAndSeverity(any(), any())).thenAnswer(invocation ->
                local.get((UUID) invocation.getArgument(0)).get((FindingSeverity) invocation.getArgument(1)));
        when(findings.countByScanIdInAndSeverity(any(), any())).thenAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            assertThat(ids).isNotEmpty().containsExactlyInAnyOrderElementsOf(local.keySet());
            FindingSeverity severity = invocation.getArgument(1);
            return ids.stream().mapToLong(id -> local.get(id).get(severity)).sum();
        });
        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(NOW);
            UiAgent agent = new UiAgent("fixture-agent");
            agent.heartbeat("IDLE", null, 2, 1, 123, 456, "fictional");
            when(agents.findAllByOrderByLastHeartbeatAtDesc()).thenReturn(List.of(agent));
            UiProperties props = new UiProperties(true, false, false, null, null, Duration.ofSeconds(30),
                    null, null, null, 1, 1, 1, 1, 0);
            UiQueryController controller = new UiQueryController(null, scans, findings, agents, null, null,
                    props, null, null, null, provider, "");

            var actual = controller.dashboard();

            var expected = new UiQueryController.Dashboard(NOW.minusSeconds(30L * 86400), 500,
                    recent.size(), selected.size(), expectedSeverity.values().stream().mapToLong(Long::longValue).sum(),
                    expectedSeverity, new UiQueryController.AgentView("fixture-agent", "IDLE", true, NOW, 2, 1, 123, 456, "fictional"));
            assertThat(actual).isEqualTo(expected);
            long singles = calls(findings, "countByScanIdAndSeverity");
            long bulk = calls(findings, "countByScanIdInAndSeverity");
            System.out.println("Dashboard selected=" + selected.size() + ", mode=" + mode + ", fallback=" + local.size()
                    + ", singleCalls=" + singles + ", bulkCalls=" + bulk + ", exactDto=true");
            assertThat(singles).isZero();
            assertThat(bulk).isEqualTo(local.isEmpty() ? 0 : FindingSeverity.values().length);
            verify(agents).findAllByOrderByLastHeartbeatAtDesc();
            if (assetIds.isEmpty()) verify(scans, never()).findLatestByAssetIds(any());
            else verify(scans).findLatestByAssetIds(assetIds);
        }
    }

    private static long valueForSum(boolean aws, Map<String, Integer> awsCounts,
            Map<FindingSeverity, Long> localCounts, FindingSeverity severity) {
        return aws ? awsCounts.getOrDefault(severity.name(), 0) : localCounts.get(severity);
    }

    private static long calls(FindingRepository repository, String method) {
        return mockingDetails(repository).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(method)).count();
    }

    private static Scan scan(int id, int assetId) {
        Scan scan = mock(Scan.class);
        Asset asset = mock(Asset.class);
        when(scan.getId()).thenReturn(new UUID(0, id));
        when(asset.getId()).thenReturn(new UUID(0, assetId));
        when(scan.getAsset()).thenReturn(asset);
        return scan;
    }
}
