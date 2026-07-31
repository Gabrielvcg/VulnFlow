package com.vulnflow.ingestion;

import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionJobRecoveryService {

    private static final String RECOVERY_ERROR = "Processing lease expired";

    private final IngestionJobRepository jobRepository;
    private final ScanRepository scanRepository;
    private final WorkerProperties properties;

    public IngestionJobRecoveryService(
            IngestionJobRepository jobRepository,
            ScanRepository scanRepository,
            WorkerProperties properties) {
        this.jobRepository = jobRepository;
        this.scanRepository = scanRepository;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecoveryResult recoverStaleJobs() {
        Instant now = Instant.now();
        List<UUID> ids = jobRepository.findStaleProcessingIds(
                now.minus(properties.staleTimeout()),
                properties.batchSize());
        int retried = 0;
        int deadLettered = 0;
        for (UUID id : ids) {
            IngestionJob job = jobRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("A stale ingestion job disappeared"));
            Scan scan = scanRepository.findByIdForUpdate(job.getScan().getId())
                    .orElseThrow(() -> new IllegalStateException("The scan for a stale job disappeared"));
            if (job.getAttemptCount() < job.getMaxAttempts()) {
                job.recoverToRetry(now, properties.backoffForAttempt(job.getAttemptCount()), RECOVERY_ERROR);
                scan.markReceived();
                retried++;
            } else {
                job.markDeadLetter(now, RECOVERY_ERROR);
                scan.markFailed(RECOVERY_ERROR);
                deadLettered++;
            }
        }
        return new RecoveryResult(retried, deadLettered);
    }
}
