package com.vulnflow.processing.port;

import java.util.Optional;
import java.util.UUID;

public interface ProcessingResultReader {
    Optional<ProcessingResultSummary> findScan(UUID scanId);

    ProcessingFindingPage findFindings(UUID scanId, String cursor, int size);
}
