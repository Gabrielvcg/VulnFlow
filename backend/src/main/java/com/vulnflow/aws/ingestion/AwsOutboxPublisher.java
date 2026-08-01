package com.vulnflow.aws.ingestion;

import com.vulnflow.contract.IngestionEventJsonCodec;
import com.vulnflow.contract.IngestionEventV1;
import com.vulnflow.processing.port.IngestionMessagePublisher;
import com.vulnflow.processing.port.ReportStorage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("aws")
public class AwsOutboxPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(AwsOutboxPublisher.class);

    private final AwsOutboxProperties properties;
    private final AwsOutboxRecoveryService recoveryService;
    private final AwsOutboxClaimService claimService;
    private final AwsOutboxCompletionService completionService;
    private final ReportStorage reportStorage;
    private final IngestionMessagePublisher publisher;
    private final IngestionEventJsonCodec codec = new IngestionEventJsonCodec();

    public AwsOutboxPublisher(
            AwsOutboxProperties properties,
            AwsOutboxRecoveryService recoveryService,
            AwsOutboxClaimService claimService,
            AwsOutboxCompletionService completionService,
            ReportStorage reportStorage,
            IngestionMessagePublisher publisher) {
        this.properties = properties;
        this.recoveryService = recoveryService;
        this.claimService = claimService;
        this.completionService = completionService;
        this.reportStorage = reportStorage;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${vulnflow.aws.outbox.poll-interval:2s}")
    public void pollScheduled() {
        if (properties.enabled()) {
            pollOnce();
        }
    }

    public int pollOnce() {
        int recovered = recoveryService.recoverStale();
        if (recovered > 0) {
            LOGGER.warn("Se recuperaron eventos de publicación abandonados: cantidad={}", recovered);
        }
        List<AwsPublicationClaim> claims = claimService.claimAvailable(properties.batchSize());
        for (AwsPublicationClaim claim : claims) {
            publish(claim);
        }
        return claims.size();
    }

    private void publish(AwsPublicationClaim claim) {
        try {
            if (!reportStorage.exists(claim.payloadKey())) {
                completionService.markFailure(
                        claim.eventId(), claim.claimToken(), "The report payload is unavailable", true);
                return;
            }
            IngestionEventV1 event = codec.deserialize(claim.eventJson());
            publisher.publish(event);
            if (!completionService.markPublished(claim.eventId(), claim.claimToken())) {
                LOGGER.warn("Se descartó una confirmación de publicación obsoleta: eventId={}", claim.eventId());
            }
        } catch (RuntimeException exception) {
            completionService.markFailure(
                    claim.eventId(),
                    claim.claimToken(),
                    "Temporary publication failure: " + exception.getClass().getSimpleName(),
                    false);
            LOGGER.warn(
                    "Falló la publicación del evento: eventId={}, intento={}, causa={}",
                    claim.eventId(), claim.attempt(), exception.getClass().getSimpleName());
        }
    }
}
