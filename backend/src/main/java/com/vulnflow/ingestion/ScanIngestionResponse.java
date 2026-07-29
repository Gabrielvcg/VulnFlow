package com.vulnflow.ingestion;

import com.vulnflow.scan.ScanStatus;
import java.util.UUID;

public record ScanIngestionResponse(
        UUID scanId,
        UUID assetId,
        ScanStatus status,
        long findingsImported,
        long criticalFindings,
        long highFindings,
        boolean duplicate) {
}

