package com.vulnflow.ingestion;

import com.vulnflow.asset.Asset;

public interface ScanSubmissionService {
    IngestionSubmission registerReceived(
            Asset asset,
            String sourceFileName,
            String contentHash,
            byte[] content);
}
