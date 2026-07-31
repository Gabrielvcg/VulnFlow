package com.vulnflow.ingestion;

import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScanStatus;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScanRecoveryService {

    static final String TIMEOUT_FAILURE_REASON = "Processing timeout exceeded";

    private static final Logger LOGGER = LoggerFactory.getLogger(ScanRecoveryService.class);

    private final ScanRepository scanRepository;
    private final IngestionProperties properties;

    public ScanRecoveryService(
            ScanRepository scanRepository,
            IngestionProperties properties) {
        this.scanRepository = scanRepository;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStaleProcessingScans() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(properties.processingTimeout());
        int recovered = scanRepository.failStaleProcessing(
                ScanStatus.PROCESSING,
                ScanStatus.FAILED,
                cutoff,
                now,
                TIMEOUT_FAILURE_REASON);
        LOGGER.info(
                "Recuperación de scans bloqueados finalizada: recuperados={}, límite={}",
                recovered,
                cutoff);
        return recovered;
    }
}
