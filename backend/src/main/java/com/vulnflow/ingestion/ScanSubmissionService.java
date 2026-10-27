package com.vulnflow.ingestion;

import com.vulnflow.asset.Asset;
import java.util.Optional;
import java.util.UUID;

public interface ScanSubmissionService {
    Optional<IngestionSubmission> findRegistered(UUID assetId, String contentHash);

    IngestionSubmission registerReceived(
            Asset asset,
            String sourceFileName,
            String contentHash,
            byte[] content);
}
