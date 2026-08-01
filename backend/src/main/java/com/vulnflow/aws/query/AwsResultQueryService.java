package com.vulnflow.aws.query;

import com.vulnflow.processing.port.ProcessingFindingPage;
import com.vulnflow.processing.port.ProcessingResultReader;
import com.vulnflow.processing.port.ProcessingResultSummary;
import com.vulnflow.shared.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("aws")
public class AwsResultQueryService {
    private final ProcessingResultReader resultReader;
    private final AwsPendingScanProjectionService pendingProjectionService;

    public AwsResultQueryService(
            ProcessingResultReader resultReader,
            AwsPendingScanProjectionService pendingProjectionService) {
        this.resultReader = resultReader;
        this.pendingProjectionService = pendingProjectionService;
    }

    public ProcessingResultSummary findScan(UUID scanId) {
        return resultReader.findScan(scanId)
                .or(() -> pendingProjectionService.findPending(scanId))
                .orElseThrow(() -> new ResourceNotFoundException("Scan", scanId));
    }

    public ProcessingFindingPage findFindings(UUID scanId, String cursor, int size) {
        ProcessingResultSummary result = resultReader.findScan(scanId).orElse(null);
        if (result == null) {
            pendingProjectionService.findPending(scanId)
                    .orElseThrow(() -> new ResourceNotFoundException("Scan", scanId));
            return new ProcessingFindingPage(java.util.List.of(), null);
        }
        return resultReader.findFindings(scanId, cursor, size);
    }
}
