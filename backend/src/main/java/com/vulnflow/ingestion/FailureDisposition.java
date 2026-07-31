package com.vulnflow.ingestion;

public enum FailureDisposition {
    RETRY_SCHEDULED,
    DEAD_LETTERED,
    IGNORED_STALE_CLAIM
}
