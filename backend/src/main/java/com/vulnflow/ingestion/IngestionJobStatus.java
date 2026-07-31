package com.vulnflow.ingestion;

public enum IngestionJobStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    COMPLETED,
    DEAD_LETTER
}
