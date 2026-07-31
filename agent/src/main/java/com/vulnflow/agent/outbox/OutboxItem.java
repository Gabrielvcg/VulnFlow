package com.vulnflow.agent.outbox;

import com.vulnflow.agent.client.UploadReceipt;
import com.vulnflow.agent.shared.SafeErrors;
import com.vulnflow.agent.target.ScanTarget;
import java.time.Instant;
import java.util.UUID;

public record OutboxItem(
        UUID id,
        String agentId,
        ScanTarget target,
        UUID assetId,
        Instant scannedAt,
        String reportFile,
        String sha256,
        long sizeBytes,
        int uploadAttempts,
        Instant nextAttemptAt,
        String lastError,
        OutboxStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant uploadedAt,
        UUID backendScanId,
        UUID backendJobId,
        String backendOutcome) {

    public OutboxItem claimed(Instant now) {
        return copy(assetId, uploadAttempts + 1, now, null, OutboxStatus.UPLOADING,
                uploadedAt, backendScanId, backendJobId, backendOutcome, now);
    }

    public OutboxItem withAsset(UUID resolvedAssetId, Instant now) {
        return copy(resolvedAssetId, uploadAttempts, nextAttemptAt, lastError, status,
                uploadedAt, backendScanId, backendJobId, backendOutcome, now);
    }

    public OutboxItem retryAt(Instant nextAttempt, String error, Instant now) {
        return copy(assetId, uploadAttempts, nextAttempt, SafeErrors.limited(error), OutboxStatus.RETRY_WAIT,
                null, null, null, null, now);
    }

    public OutboxItem deadLetter(String error, Instant now) {
        return copy(assetId, uploadAttempts, nextAttemptAt, SafeErrors.limited(error), OutboxStatus.DEAD_LETTER,
                null, null, null, null, now);
    }

    public OutboxItem uploaded(UploadReceipt receipt, Instant now) {
        return copy(assetId, uploadAttempts, nextAttemptAt, null, OutboxStatus.UPLOADED,
                now, receipt.scanId(), receipt.jobId(), receipt.outcome(), now);
    }

    public OutboxItem recover(Instant now) {
        if (status != OutboxStatus.UPLOADING) {
            return this;
        }
        return retryAt(now, "Upload interrupted by agent shutdown", now);
    }

    private OutboxItem copy(
            UUID nextAssetId,
            int nextUploadAttempts,
            Instant nextAttempt,
            String nextLastError,
            OutboxStatus nextStatus,
            Instant nextUploadedAt,
            UUID nextBackendScanId,
            UUID nextBackendJobId,
            String nextBackendOutcome,
            Instant nextUpdatedAt) {
        return new OutboxItem(
                id, agentId, target, nextAssetId, scannedAt, reportFile, sha256, sizeBytes,
                nextUploadAttempts, nextAttempt, nextLastError, nextStatus, createdAt, nextUpdatedAt,
                nextUploadedAt, nextBackendScanId, nextBackendJobId, nextBackendOutcome);
    }
}
