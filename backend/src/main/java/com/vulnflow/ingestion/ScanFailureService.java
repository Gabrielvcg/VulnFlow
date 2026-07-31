package com.vulnflow.ingestion;

import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScanStatus;
import com.vulnflow.shared.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScanFailureService {

    private final ScanRepository scanRepository;

    public ScanFailureService(ScanRepository scanRepository) {
        this.scanRepository = scanRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID scanId, String failureReason) {
        Scan scan = scanRepository.findByIdForUpdate(scanId)
                .orElseThrow(() -> new ResourceNotFoundException("Scan", scanId));
        if (scan.getStatus() == ScanStatus.PROCESSING) {
            scan.markFailed(failureReason);
        }
    }
}
