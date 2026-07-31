package com.vulnflow.ingestion;

public enum ScanIngestionOutcome {
    IMPORTED,
    RETRIED,
    DUPLICATE,
    ALREADY_PROCESSING
}
