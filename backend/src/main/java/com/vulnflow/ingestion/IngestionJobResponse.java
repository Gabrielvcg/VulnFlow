package com.vulnflow.ingestion;

import java.time.Instant;
import java.util.UUID;

public record IngestionJobResponse(
        UUID id,
        UUID scanId,
        UUID assetId,
        IngestionJobStatus status,
        int attemptCount,
        int maxAttempts,
        Instant availableAt,
        Instant lockedAt,
        Instant completedAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public static IngestionJobResponse from(IngestionJob job) {
        return new IngestionJobResponse(
                job.getId(),
                job.getScan().getId(),
                job.getScan().getAsset().getId(),
                job.getStatus(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getAvailableAt(),
                job.getLockedAt(),
                job.getCompletedAt(),
                job.getLastError(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
