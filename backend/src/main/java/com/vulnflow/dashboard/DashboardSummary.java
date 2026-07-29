package com.vulnflow.dashboard;

public record DashboardSummary(
        long totalAssets,
        long totalScans,
        long totalFindings,
        long openFindings,
        long criticalFindings,
        long highFindings,
        long knownExploitedFindings) {
}

