package com.vulnflow.ingestion;

import com.vulnflow.scan.ScanStatus;
import java.util.UUID;

public record ScanIngestionResponse(
        UUID scanId,
        UUID jobId,
        UUID assetId,
        ScanStatus scanStatus,
        IngestionJobStatus jobStatus,
        ScanIngestionOutcome outcome,
        UUID eventId,
        String publicationStatus) {

    public ScanIngestionResponse(
            UUID scanId,
            UUID jobId,
            UUID assetId,
            ScanStatus scanStatus,
            IngestionJobStatus jobStatus,
            ScanIngestionOutcome outcome) {
        this(scanId, jobId, assetId, scanStatus, jobStatus, outcome, null, null);
    }
}
