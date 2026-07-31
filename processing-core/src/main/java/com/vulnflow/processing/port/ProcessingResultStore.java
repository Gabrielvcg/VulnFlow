package com.vulnflow.processing.port;

import com.vulnflow.processing.ProcessedVulnerabilityReport;

public interface ProcessingResultStore<C> {
    ProcessingStoreOutcome store(C context, ProcessedVulnerabilityReport report);
}
