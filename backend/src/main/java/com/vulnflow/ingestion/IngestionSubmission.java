package com.vulnflow.ingestion;

import com.vulnflow.scan.ScanStatus;
import java.util.UUID;

public record IngestionSubmission(
        UUID scanId,
        UUID jobId,
        UUID assetId,
        ScanStatus scanStatus,
        IngestionJobStatus jobStatus,
        ScanIngestionOutcome outcome) {
}
