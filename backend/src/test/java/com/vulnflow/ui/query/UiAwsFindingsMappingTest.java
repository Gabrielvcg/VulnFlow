package com.vulnflow.ui.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.FindingSeverity;
import com.vulnflow.processing.port.ProcessingFindingPage;
import com.vulnflow.processing.port.ProcessingFindingResult;
import com.vulnflow.processing.port.ProcessingResultReader;
import com.vulnflow.processing.port.ProcessingResultSummary;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.ui.query.UiQueryController.FindingView;
import com.vulnflow.ui.query.UiQueryController.FindingsPage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;

class UiAwsFindingsMappingTest {
    private static final UUID SCAN = new UUID(0, 1);

    static Stream<Arguments> pagesAndFilters() {
        return Stream.of(
                Arguments.of(0, 25, null, null),
                Arguments.of(1, 100, "mixed", FindingSeverity.HIGH),
                Arguments.of(0, 25, "  pAcKaGe-1 ", null),
                Arguments.of(200, 25, null, null),
                Arguments.of(401, 25, null, null),
                Arguments.of(Integer.MAX_VALUE, 100, null, null),
                Arguments.of(-1, 1, null, null),
                Arguments.of(0, 101, "no match", null),
                Arguments.of(0, 0, "cve-3", FindingSeverity.LOW));
    }

    @ParameterizedTest
    @MethodSource("pagesAndFilters")
    void preservesTheExistingFullSortDtoAndReaderTraversal(int page, int size, String query, FindingSeverity severity) {
        List<ProcessingFindingResult> rows = new ArrayList<>();
        for (int index = 0; index < 10_000; index++) {
            rows.add(row("key-" + index, "CVE-" + (index % 37),
                    index % 5 == 0 ? null : "Package-" + (index % 3),
                    index % 11 == 0 ? null : "MiXeD title", index, index % 101,
                    index % 2 == 0 ? "HIGH" : "LOW"));
        }
        Reader reader = new Reader(rows);

        FindingsPage actual = controller(reader).resultFindings(SCAN, page, size, query, severity, null);

        assertThat(actual).isEqualTo(existingFullSort(rows, page, size, query, severity));
        assertThat(reader.cursors).hasSize(100);
        assertThat(reader.cursors.get(0)).isNull();
        assertThat(reader.cursors.subList(1, 100)).containsExactlyElementsOf(
                java.util.stream.IntStream.range(1, 100).mapToObj(i -> Integer.toString(i * 100)).toList());
    }

    @Test
    void preservesEncounterOrderForExactComparatorTiesWithDifferentPayloads() {
        var rows = List.of(row("same", "CVE-1", null, null, 1, 80, "HIGH"),
                row("same", "CVE-1", "package", "title", 2, 80, "HIGH"),
                row("other", "CVE-2", null, null, 3, 90, "LOW"));
        FindingsPage page = controller(new Reader(rows)).resultFindings(SCAN, 1, 1, null, null, null);
        assertThat(page).isEqualTo(existingFullSort(rows, 1, 1, null, null));
        assertThat(page.content()).singleElement().extracting(FindingView::installedVersion).isEqualTo("v1");
    }

    @Test
    void returnsAnExactEmptyPageWithoutInventingACursor() {
        FindingsPage page = controller(new Reader(List.of())).resultFindings(SCAN, 0, 25, null, null, null);
        assertThat(page).isEqualTo(new FindingsPage(List.of(), null, 0, 0, 0, false, true));
    }

    @Test
    void stillMatchesNullVulnerabilityIdsThroughPackageOrTitle() {
        var rows = List.of(row("null-cve", null, "Package-only", null, 1, 90, "HIGH"),
                row("other", "CVE-2", null, "Title-only", 2, 10, "LOW"));
        for (String query : List.of("PACKAGE-ONLY", "title-only", "no match")) {
            assertThat(controller(new Reader(rows)).resultFindings(SCAN, 0, 25, query, null, null))
                    .isEqualTo(existingFullSort(rows, 0, 25, query, null));
        }
    }

    @Test
    void preservesNaturalComparatorFailureForNullIdentityTies() {
        for (var rows : List.of(
                List.of(row("a", null, "pkg", null, 1, 80, "HIGH"), row("b", "CVE-2", "pkg", null, 2, 80, "HIGH")),
                List.of(row(null, "CVE-1", "pkg", null, 1, 80, "HIGH"), row(null, "CVE-1", "pkg", null, 2, 80, "HIGH")))) {
            assertThatThrownBy(() -> existingFullSort(rows, 0, 25, null, null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> controller(new Reader(rows)).resultFindings(SCAN, 0, 25, null, null, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // Frozen pre-change selection oracle from R07: map before filtering, stable full sort, then slice.
    private static FindingsPage existingFullSort(List<ProcessingFindingResult> rows, int page, int size,
            String query, FindingSeverity severity) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(size, 100));
        List<FindingView> matches = rows.stream().map(FindingView::from)
                .filter(f -> (severity == null || severity.name().equals(f.severity()))
                        && (needle.isEmpty() || contains(f.vulnerabilityId(), needle)
                        || contains(f.packageName(), needle) || contains(f.title(), needle)))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        matches.sort(Comparator.comparingInt(FindingView::riskScore).reversed()
                .thenComparing(FindingView::vulnerabilityId).thenComparing(FindingView::id));
        int start = (int) Math.min((long) Math.max(page, 0) * limit, matches.size());
        int end = Math.min(start + limit, matches.size());
        return new FindingsPage(matches.subList(start, end), null, Math.max(page, 0),
                (matches.size() + limit - 1) / limit, matches.size(), false, true);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static ProcessingFindingResult row(String key, String cve, String pkg, String title,
            int index, int risk, String severity) {
        return new ProcessingFindingResult(key, cve, pkg, "v" + index, null,
                com.vulnflow.processing.FindingSeverity.valueOf(severity), title, null, false, risk);
    }

    @SuppressWarnings("unchecked")
    private static UiQueryController controller(Reader reader) {
        ScanRepository scans = mock(ScanRepository.class);
        when(scans.findById(SCAN)).thenReturn(Optional.of(mock(Scan.class)));
        FindingRepository findings = mock(FindingRepository.class);
        ObjectProvider<ProcessingResultReader> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(reader);
        return new UiQueryController(null, scans, findings, null, null, null, null, null, null, null, provider, "");
    }

    private static final class Reader implements ProcessingResultReader {
        private final List<ProcessingFindingResult> rows;
        private final List<String> cursors = new ArrayList<>();
        private Reader(List<ProcessingFindingResult> rows) { this.rows = rows; }
        @Override public Optional<ProcessingResultSummary> findScan(UUID id) { throw new AssertionError(); }
        @Override public ProcessingFindingPage findFindings(UUID id, String cursor, int size) {
            assertThat(id).isEqualTo(SCAN);
            assertThat(size).isEqualTo(100);
            cursors.add(cursor);
            int start = cursor == null ? 0 : Integer.parseInt(cursor);
            int end = Math.min(start + size, rows.size());
            return new ProcessingFindingPage(rows.subList(start, end), end < rows.size() ? Integer.toString(end) : null);
        }
    }
}
