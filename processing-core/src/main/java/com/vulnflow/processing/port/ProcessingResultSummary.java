package com.vulnflow.processing.port;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ProcessingResultSummary(
        UUID eventId,
        UUID scanId,
        UUID assetId,
        UUID correlationId,
        String contentHash,
        String scanner,
        String scannerVersion,
        ProcessingResultStatus status,
        Instant receivedAt,
        Instant completedAt,
        int findingCount,
        Map<String, Integer> severitySummary,
        String errorCode,
        String safeError) {
    public ProcessingResultSummary {
        scanId = Objects.requireNonNull(scanId, "scanId");
        assetId = Objects.requireNonNull(assetId, "assetId");
        status = Objects.requireNonNull(status, "status");
        severitySummary = severitySummary == null ? Map.of() : Map.copyOf(severitySummary);
        if (findingCount < 0) {
            throw new IllegalArgumentException("findingCount must not be negative");
        }
    }
}
