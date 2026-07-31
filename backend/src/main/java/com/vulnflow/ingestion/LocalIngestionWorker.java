package com.vulnflow.ingestion;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LocalIngestionWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalIngestionWorker.class);

    private final WorkerProperties properties;
    private final IngestionJobRecoveryService recoveryService;
    private final JobClaimService claimService;
    private final IngestionJobProcessor processor;
    private final IngestionMetrics metrics;

    public LocalIngestionWorker(
            WorkerProperties properties,
            IngestionJobRecoveryService recoveryService,
            JobClaimService claimService,
            IngestionJobProcessor processor,
            IngestionMetrics metrics) {
        this.properties = properties;
        this.recoveryService = recoveryService;
        this.claimService = claimService;
        this.processor = processor;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${vulnflow.worker.poll-interval:2s}")
    public void pollScheduled() {
        if (properties.enabled()) {
            pollOnce();
        }
    }

    public int pollOnce() {
        RecoveryResult recovery = recoveryService.recoverStaleJobs();
        for (int index = 0; index < recovery.retried(); index++) {
            metrics.jobRetried();
        }
        for (int index = 0; index < recovery.deadLettered(); index++) {
            metrics.jobDeadLettered();
        }

        List<JobClaim> claims = claimService.claimAvailable(properties.batchSize());
        for (JobClaim claim : claims) {
            try {
                processor.process(claim);
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "No se pudo finalizar el ciclo de un trabajo: jobId={}, scanId={}, assetId={}, intento={}",
                        claim.jobId(), claim.scanId(), claim.assetId(), claim.attempt(), exception);
            }
        }
        return claims.size();
    }
}
