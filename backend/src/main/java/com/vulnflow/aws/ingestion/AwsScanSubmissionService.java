package com.vulnflow.aws.ingestion;

import com.vulnflow.asset.Asset;
import com.vulnflow.ingestion.IngestionSubmission;
import com.vulnflow.ingestion.ScanSubmissionService;
import com.vulnflow.processing.port.ReportStorage;
import java.util.UUID;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("aws")
public class AwsScanSubmissionService implements ScanSubmissionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AwsScanSubmissionService.class);

    private final ReportStorage reportStorage;
    private final AwsScanRegistrationTransaction registrationTransaction;

    public AwsScanSubmissionService(
            ReportStorage reportStorage,
            AwsScanRegistrationTransaction registrationTransaction) {
        this.reportStorage = reportStorage;
        this.registrationTransaction = registrationTransaction;
    }

    @Override
    public Optional<IngestionSubmission> findRegistered(UUID assetId, String contentHash) {
        return registrationTransaction.findRegistered(assetId, contentHash);
    }
    @Override
    public IngestionSubmission registerReceived(
            Asset asset,
            String sourceFileName,
            String contentHash,
            byte[] content) {
        UUID candidateScanId = UUID.randomUUID();
        String payloadKey = reportStorage.store(candidateScanId, content);
        // A thrown registration call cannot prove rollback: the commit acknowledgement may have been lost.
        // Retain the payload unless a successful result confirms that this upload is not referenced.
        AwsRegistrationResult result = registrationTransaction.register(
                candidateScanId,
                asset.getId(),
                sourceFileName,
                contentHash,
                payloadKey);
        if (!result.ownsUploadedPayload()) {
            compensate(payloadKey);
        }
        return result.submission();
    }

    private void compensate(String payloadKey) {
        try {
            reportStorage.delete(payloadKey);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "No se pudo compensar un payload S3 sin referencia PostgreSQL: causa={}",
                    exception.getClass().getSimpleName());
        }
    }
}
