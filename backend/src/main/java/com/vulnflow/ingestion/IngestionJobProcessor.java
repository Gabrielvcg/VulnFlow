package com.vulnflow.ingestion;

import com.vulnflow.shared.exception.InvalidReportException;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IngestionJobProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionJobProcessor.class);
    private static final String VALIDATION_ERROR = "Report validation failed";
    private static final String MISSING_PAYLOAD_ERROR = "Stored report payload is unavailable";
    private static final String TRANSIENT_ERROR = "Temporary processing failure";

    private final ReportStorage reportStorage;
    private final VulnerabilityReportParser reportParser;
    private final IngestionPersistenceService persistenceService;
    private final JobFailureService failureService;
    private final IngestionMetrics metrics;

    public IngestionJobProcessor(
            ReportStorage reportStorage,
            VulnerabilityReportParser reportParser,
            IngestionPersistenceService persistenceService,
            JobFailureService failureService,
            IngestionMetrics metrics) {
        this.reportStorage = reportStorage;
        this.reportParser = reportParser;
        this.persistenceService = persistenceService;
        this.failureService = failureService;
        this.metrics = metrics;
    }

    public void process(JobClaim claim) {
        Timer.Sample timer = metrics.startProcessing();
        try {
            byte[] content = reportStorage.load(claim.payloadKey());
            ParsedVulnerabilityReport report = reportParser.parse(content);
            persistenceService.complete(claim.jobId(), claim.attempt(), report);
            metrics.jobCompleted();
            LOGGER.info(
                    "Trabajo de ingesta completado: jobId={}, scanId={}, assetId={}, intento={}, resultado=COMPLETED",
                    claim.jobId(), claim.scanId(), claim.assetId(), claim.attempt());
        } catch (PayloadNotFoundException exception) {
            fail(claim, false, MISSING_PAYLOAD_ERROR, exception);
        } catch (InvalidReportException exception) {
            fail(claim, false, VALIDATION_ERROR, exception);
        } catch (StaleJobClaimException exception) {
            LOGGER.warn(
                    "Se ignoró una reclamación obsoleta: jobId={}, scanId={}, assetId={}, intento={}",
                    claim.jobId(), claim.scanId(), claim.assetId(), claim.attempt());
        } catch (RuntimeException exception) {
            fail(claim, true, TRANSIENT_ERROR, exception);
        } finally {
            metrics.stopProcessing(timer);
        }
    }

    private void fail(
            JobClaim claim,
            boolean retryable,
            String safeError,
            RuntimeException exception) {
        LOGGER.warn(
                "Falló un trabajo de ingesta: jobId={}, scanId={}, assetId={}, intento={}, reintentable={}, causa={}",
                claim.jobId(),
                claim.scanId(),
                claim.assetId(),
                claim.attempt(),
                retryable,
                exception.getClass().getSimpleName(),
                exception);
        FailureDisposition disposition = failureService.handleFailure(
                claim.jobId(), claim.attempt(), retryable, safeError);
        if (disposition == FailureDisposition.RETRY_SCHEDULED) {
            metrics.jobRetried();
        } else if (disposition == FailureDisposition.DEAD_LETTERED) {
            metrics.jobDeadLettered();
        }
    }
}
