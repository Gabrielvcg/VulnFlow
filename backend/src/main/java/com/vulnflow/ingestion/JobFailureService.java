package com.vulnflow.ingestion;

import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.shared.exception.ResourceNotFoundException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobFailureService {

    private final IngestionJobRepository jobRepository;
    private final ScanRepository scanRepository;
    private final WorkerProperties properties;

    public JobFailureService(
            IngestionJobRepository jobRepository,
            ScanRepository scanRepository,
            WorkerProperties properties) {
        this.jobRepository = jobRepository;
        this.scanRepository = scanRepository;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureDisposition handleFailure(
            UUID jobId,
            UUID expectedClaimToken,
            boolean retryable,
            String safeError) {
        IngestionJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("IngestionJob", jobId));
        if (job.getStatus() != IngestionJobStatus.PROCESSING
                || !Objects.equals(job.getClaimToken(), expectedClaimToken)) {
            return FailureDisposition.IGNORED_STALE_CLAIM;
        }
        Scan scan = scanRepository.findByIdForUpdate(job.getScan().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Scan", job.getScan().getId()));
        Instant now = Instant.now();
        if (retryable && job.getAttemptCount() < job.getMaxAttempts()) {
            job.scheduleRetry(now, properties.backoffForAttempt(job.getAttemptCount()), safeError);
            scan.markReceived();
            return FailureDisposition.RETRY_SCHEDULED;
        }
        job.markDeadLetter(now, safeError);
        scan.markFailed(safeError);
        return FailureDisposition.DEAD_LETTERED;
    }
}
