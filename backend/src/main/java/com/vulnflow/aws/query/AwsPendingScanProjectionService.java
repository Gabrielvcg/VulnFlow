package com.vulnflow.aws.query;

import com.vulnflow.aws.ingestion.AwsPublicationOutbox;
import com.vulnflow.aws.ingestion.AwsPublicationOutboxRepository;
import com.vulnflow.aws.ingestion.AwsPublicationStatus;
import com.vulnflow.contract.IngestionEventJsonCodec;
import com.vulnflow.contract.IngestionEventV1;
import com.vulnflow.processing.port.ProcessingResultStatus;
import com.vulnflow.processing.port.ProcessingResultSummary;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("aws")
public class AwsPendingScanProjectionService {
    private final ScanRepository scanRepository;
    private final AwsPublicationOutboxRepository outboxRepository;
    private final IngestionEventJsonCodec codec = new IngestionEventJsonCodec();

    public AwsPendingScanProjectionService(
            ScanRepository scanRepository,
            AwsPublicationOutboxRepository outboxRepository) {
        this.scanRepository = scanRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional(readOnly = true)
    public Optional<ProcessingResultSummary> findPending(UUID scanId) {
        Scan scan = scanRepository.findById(scanId).orElse(null);
        if (scan == null) {
            return Optional.empty();
        }
        AwsPublicationOutbox outbox = outboxRepository.findByScanId(scanId).orElse(null);
        IngestionEventV1 event = outbox == null ? null : codec.deserialize(outbox.getEventJson());
        return Optional.of(new ProcessingResultSummary(
                event == null ? null : event.eventId(),
                scan.getId(),
                scan.getAsset().getId(),
                event == null ? null : event.correlationId(),
                scan.getContentHash(),
                scan.getScanner().name(),
                null,
                status(outbox),
                scan.getReceivedAt(),
                null,
                0,
                Map.of(),
                outbox != null && outbox.getStatus() == AwsPublicationStatus.FAILED
                        ? "PUBLICATION_FAILED" : null,
                outbox == null ? null : outbox.getLastError()));
    }

    private ProcessingResultStatus status(AwsPublicationOutbox outbox) {
        if (outbox == null) {
            return ProcessingResultStatus.RECEIVED;
        }
        return switch (outbox.getStatus()) {
            case PUBLISH_PENDING -> ProcessingResultStatus.PUBLISH_PENDING;
            case PUBLISHING, PUBLISHED -> ProcessingResultStatus.QUEUED;
            case FAILED -> ProcessingResultStatus.FAILED;
        };
    }
}
