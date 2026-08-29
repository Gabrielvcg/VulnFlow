package com.vulnflow.processing.port;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ProcessingResultReader {
    Optional<ProcessingResultSummary> findScan(UUID scanId);

    default Map<UUID, ProcessingResultSummary> findScans(Collection<UUID> scanIds) {
        Map<UUID, ProcessingResultSummary> results = new LinkedHashMap<>();
        for (UUID scanId : scanIds) {
            findScan(scanId).ifPresent(summary -> results.put(scanId, summary));
        }
        return results;
    }

    ProcessingFindingPage findFindings(UUID scanId, String cursor, int size);
}
