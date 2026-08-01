package com.vulnflow.processing.port;

public enum ProcessingResultStatus {
    RECEIVED,
    PUBLISH_PENDING,
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED
}
