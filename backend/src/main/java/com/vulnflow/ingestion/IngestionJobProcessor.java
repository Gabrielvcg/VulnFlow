package com.vulnflow.ingestion;

import com.vulnflow.processing.ProcessedVulnerabilityReport;
import com.vulnflow.processing.VulnerabilityReportProcessingRequest;
import com.vulnflow.processing.VulnerabilityReportProcessor;
import com.vulnflow.processing.port.ReportStorage;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IngestionJobProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionJobProcessor.class);
    private final ReportStorage reportStorage;
    private final VulnerabilityReportProcessor reportProcessor;
    private final IngestionPersistenceService persistenceService;
    private final JobFailureService failureService;
    private final JobFailureClassifier failureClassifier;
    private final IngestionMetrics metrics;

    public IngestionJobProcessor(
            ReportStorage reportStorage,
            VulnerabilityReportProcessor reportProcessor,
            IngestionPersistenceService persistenceService,
            JobFailureService failureService,
            JobFailureClassifier failureClassifier,
            IngestionMetrics metrics) {
        this.reportStorage = reportStorage;
        this.reportProcessor = reportProcessor;
        this.persistenceService = persistenceService;
        this.failureService = failureService;
        this.failureClassifier = failureClassifier;
        this.metrics = metrics;
    }

    public void process(JobClaim claim) {
        Timer.Sample timer = metrics.startProcessing();
        try {
            byte[] content = reportStorage.load(claim.payloadKey());
            ProcessedVulnerabilityReport report = reportProcessor.process(
                    new VulnerabilityReportProcessingRequest(
                            claim.scanId(), claim.assetId(), claim.contentHash(), content));
            persistenceService.store(new LocalCompletionContext(claim.jobId(), claim.claimToken()), report);
            metrics.jobCompleted();
            LOGGER.info(
                    "Trabajo de ingesta completado: jobId={}, scanId={}, assetId={}, intento={}, resultado=COMPLETED",
                    claim.jobId(), claim.scanId(), claim.assetId(), claim.attempt());
        } catch (StaleJobClaimException exception) {
            LOGGER.warn(
                    "Se ignoró una reclamación obsoleta: jobId={}, scanId={}, assetId={}, intento={}",
                    claim.jobId(), claim.scanId(), claim.assetId(), claim.attempt());
        } catch (RuntimeException exception) {
            fail(claim, failureClassifier.classify(exception), exception);
        } finally {
            metrics.stopProcessing(timer);
        }
    }

    private void fail(
            JobClaim claim,
            JobFailureClassification classification,
            RuntimeException exception) {
        LOGGER.warn(
                "Falló un trabajo de ingesta: jobId={}, scanId={}, assetId={}, intento={}, reintentable={}, causa={}",
                claim.jobId(),
                claim.scanId(),
                claim.assetId(),
                claim.attempt(),
                classification.retryable(),
                exception.getClass().getSimpleName(),
                exception);
        FailureDisposition disposition = failureService.handleFailure(
                claim.jobId(),
                claim.claimToken(),
                classification.retryable(),
                classification.safeError());
        if (disposition == FailureDisposition.RETRY_SCHEDULED) {
            metrics.jobRetried();
        } else if (disposition == FailureDisposition.DEAD_LETTERED) {
            metrics.jobDeadLettered();
        }
    }
}
