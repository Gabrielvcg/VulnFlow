package com.vulnflow.processing;

public record NormalizedFinding(
        String vulnerabilityId,
        String packageName,
        String installedVersion,
        String fixedVersion,
        FindingSeverity severity,
        String title,
        String description,
        boolean knownExploited,
        int riskScore) {
}
