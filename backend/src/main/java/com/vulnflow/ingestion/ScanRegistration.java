package com.vulnflow.ingestion;

import com.vulnflow.scan.ScanStatus;
import java.util.UUID;

public record ScanRegistration(
        UUID scanId,
        UUID assetId,
        ScanStatus status,
        ScanIngestionOutcome outcome) {
}
