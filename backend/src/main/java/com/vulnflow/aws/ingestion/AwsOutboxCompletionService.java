package com.vulnflow.aws.ingestion;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("aws")
public class AwsOutboxCompletionService {
    private final AwsPublicationOutboxRepository repository;
    private final AwsOutboxProperties properties;

    public AwsOutboxCompletionService(
            AwsPublicationOutboxRepository repository,
            AwsOutboxProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(UUID eventId, UUID claimToken) {
        AwsPublicationOutbox event = lockCurrentClaim(eventId, claimToken);
        if (event == null) {
            return false;
        }
        event.markPublished(Instant.now());
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailure(UUID eventId, UUID claimToken, String safeError, boolean permanent) {
        AwsPublicationOutbox event = lockCurrentClaim(eventId, claimToken);
        if (event == null) {
            return false;
        }
        event.markPublicationFailure(
                Instant.now(),
                properties.backoffForAttempt(event.getAttemptCount()),
                safeError,
                permanent);
        return true;
    }

    private AwsPublicationOutbox lockCurrentClaim(UUID eventId, UUID claimToken) {
        AwsPublicationOutbox event = repository.findByEventIdForUpdate(eventId).orElse(null);
        if (event == null
                || event.getStatus() != AwsPublicationStatus.PUBLISHING
                || !claimToken.equals(event.getClaimToken())) {
            return null;
        }
        return event;
    }
}
