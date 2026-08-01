package com.vulnflow.processing.port;

import com.vulnflow.processing.FindingSeverity;

public record ProcessingFindingResult(
        String findingKey,
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
