package com.vulnflow.ingestion;

import com.vulnflow.asset.Asset;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScanStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ScanRegistrationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScanRegistrationService.class);

    private final ScanRepository scanRepository;
    private final IngestionJobRepository jobRepository;
    private final ReportStorage reportStorage;
    private final WorkerProperties workerProperties;

    public ScanRegistrationService(
            ScanRepository scanRepository,
            IngestionJobRepository jobRepository,
            ReportStorage reportStorage,
            WorkerProperties workerProperties) {
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
        this.reportStorage = reportStorage;
        this.workerProperties = workerProperties;
    }

    @Transactional
    public IngestionSubmission registerReceived(
            Asset asset,
            String sourceFileName,
            String contentHash,
            byte[] content) {
        UUID candidateId = UUID.randomUUID();
        int inserted = scanRepository.insertReceivedIfAbsent(
                candidateId,
                asset.getId(),
                sourceFileName,
                contentHash,
                Instant.now());

        Scan scan = scanRepository.findByAssetIdAndContentHashForUpdate(asset.getId(), contentHash)
                .orElseThrow(() -> new IllegalStateException("The registered scan could not be loaded"));

        if (inserted == 1) {
            String payloadKey = reportStorage.store(scan.getId(), content);
            registerRollbackCleanup(payloadKey);
            IngestionJob job = jobRepository.save(
                    new IngestionJob(scan, payloadKey, workerProperties.maxAttempts()));
            return submission(scan, job, ScanIngestionOutcome.ACCEPTED);
        }

        Optional<IngestionJob> existingJob = jobRepository.findByScanId(scan.getId());
        return existingSubmission(scan, existingJob.orElse(null));
    }

    private IngestionSubmission existingSubmission(Scan scan, IngestionJob job) {
        if (scan.getStatus() == ScanStatus.COMPLETED) {
            return submission(scan, job, ScanIngestionOutcome.DUPLICATE);
        }
        if (scan.getStatus() == ScanStatus.FAILED
                || (job != null && job.getStatus() == IngestionJobStatus.DEAD_LETTER)) {
            return submission(scan, job, ScanIngestionOutcome.DEAD_LETTER);
        }
        if (job == null) {
            throw new IllegalStateException("The active scan has no ingestion job");
        }
        if (job.getStatus() == IngestionJobStatus.PROCESSING) {
            return submission(scan, job, ScanIngestionOutcome.ALREADY_PROCESSING);
        }
        return submission(scan, job, ScanIngestionOutcome.ALREADY_QUEUED);
    }

    private IngestionSubmission submission(
            Scan scan,
            IngestionJob job,
            ScanIngestionOutcome outcome) {
        return new IngestionSubmission(
                scan.getId(),
                job == null ? null : job.getId(),
                scan.getAsset().getId(),
                scan.getStatus(),
                job == null ? null : job.getStatus(),
                outcome);
    }

    private void registerRollbackCleanup(String payloadKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    try {
                        reportStorage.delete(payloadKey);
                    } catch (RuntimeException exception) {
                        LOGGER.error(
                                "No se pudo limpiar un payload tras revertir el registro: causa={}",
                                exception.getClass().getSimpleName());
                    }
                }
            }
        });
    }
}
