package com.vulnflow.aws.ingestion;

import com.vulnflow.contract.IngestionEventJsonCodec;
import com.vulnflow.contract.IngestionEventV1;
import com.vulnflow.ingestion.IngestionSubmission;
import com.vulnflow.ingestion.ScanIngestionOutcome;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScanStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("aws")
public class AwsScanRegistrationTransaction {
    private final ScanRepository scanRepository;
    private final AwsPublicationOutboxRepository outboxRepository;
    private final AwsOutboxProperties properties;
    private final IngestionEventJsonCodec codec = new IngestionEventJsonCodec();

    public AwsScanRegistrationTransaction(
            ScanRepository scanRepository,
            AwsPublicationOutboxRepository outboxRepository,
            AwsOutboxProperties properties) {
        this.scanRepository = scanRepository;
        this.outboxRepository = outboxRepository;
        this.properties = properties;
    }

    @Transactional
    public AwsRegistrationResult register(
            UUID candidateScanId,
            UUID assetId,
            String sourceFileName,
            String contentHash,
            String uploadedPayloadKey) {
        Instant now = Instant.now();
        int inserted = scanRepository.insertReceivedIfAbsent(
                candidateScanId, assetId, sourceFileName, contentHash, now);
        Scan scan = scanRepository.findByAssetIdAndContentHashForUpdate(assetId, contentHash)
                .orElseThrow(() -> new IllegalStateException("The registered scan could not be loaded"));

        Optional<AwsPublicationOutbox> existing = outboxRepository.findByScanId(scan.getId());
        if (existing.isPresent()) {
            return existing(scan, existing.get());
        }
        if (inserted == 0 && scan.getStatus() == ScanStatus.COMPLETED) {
            return new AwsRegistrationResult(submission(scan, null, null, ScanIngestionOutcome.DUPLICATE), false);
        }
        if (inserted == 0) {
            scan.markReceived();
        }

        UUID eventId = UUID.randomUUID();
        IngestionEventV1 event = new IngestionEventV1(
                IngestionEventV1.VERSION,
                eventId,
                scan.getId(),
                assetId,
                uploadedPayloadKey,
                contentHash,
                "TRIVY",
                now,
                UUID.randomUUID());
        AwsPublicationOutbox outbox = outboxRepository.save(new AwsPublicationOutbox(
                eventId,
                scan,
                uploadedPayloadKey,
                codec.serialize(event),
                properties.maxAttempts(),
                now));
        return new AwsRegistrationResult(
                submission(scan, outbox.getEventId(), outbox.getStatus(), ScanIngestionOutcome.ACCEPTED),
                true);
    }

    private AwsRegistrationResult existing(Scan scan, AwsPublicationOutbox outbox) {
        ScanIngestionOutcome outcome = switch (outbox.getStatus()) {
            case FAILED -> ScanIngestionOutcome.DEAD_LETTER;
            case PUBLISHING -> ScanIngestionOutcome.ALREADY_PROCESSING;
            case PUBLISH_PENDING, PUBLISHED -> ScanIngestionOutcome.ALREADY_QUEUED;
        };
        return new AwsRegistrationResult(
                submission(scan, outbox.getEventId(), outbox.getStatus(), outcome),
                false);
    }

    private IngestionSubmission submission(
            Scan scan,
            UUID eventId,
            AwsPublicationStatus status,
            ScanIngestionOutcome outcome) {
        return new IngestionSubmission(
                scan.getId(),
                null,
                scan.getAsset().getId(),
                scan.getStatus(),
                null,
                outcome,
                eventId,
                status == null ? null : status.name());
    }
}
