package com.vulnflow.ingestion;

public enum ScanIngestionOutcome {
    ACCEPTED,
    DUPLICATE,
    ALREADY_QUEUED,
    ALREADY_PROCESSING,
    DEAD_LETTER
}
