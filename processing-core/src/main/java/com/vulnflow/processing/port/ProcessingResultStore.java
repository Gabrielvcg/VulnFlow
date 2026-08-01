package com.vulnflow.processing.port;

import com.vulnflow.processing.ProcessedVulnerabilityReport;

public interface ProcessingResultStore<C> {
    default boolean isFinalized(C context) {
        return false;
    }

    ProcessingStoreOutcome store(C context, ProcessedVulnerabilityReport report);

    default ProcessingStoreOutcome storeFailure(C context, ProcessingFailure failure) {
        throw new UnsupportedOperationException("Failure persistence is not supported by this result store");
    }
}
