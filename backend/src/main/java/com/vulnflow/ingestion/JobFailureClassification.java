package com.vulnflow.ingestion;

public record JobFailureClassification(boolean retryable, String safeError) {
}
