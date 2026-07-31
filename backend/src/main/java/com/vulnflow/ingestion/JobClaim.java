package com.vulnflow.ingestion;

import java.util.UUID;

public record JobClaim(
        UUID jobId,
        UUID scanId,
        UUID assetId,
        String payloadKey,
        String contentHash,
        UUID claimToken,
        int attempt,
        int maxAttempts) {
}
