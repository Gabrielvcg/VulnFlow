package com.vulnflow.aws.ingestion;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("aws")
public class AwsOutboxRecoveryService {
    private final AwsPublicationOutboxRepository repository;
    private final AwsOutboxProperties properties;

    public AwsOutboxRecoveryService(
            AwsPublicationOutboxRepository repository,
            AwsOutboxProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStale() {
        Instant now = Instant.now();
        int recovered = 0;
        for (UUID id : repository.findStaleIds(now.minus(properties.staleTimeout()), properties.batchSize())) {
            AwsPublicationOutbox event = repository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("A stale outbox event disappeared"));
            event.recover(now, properties.backoffForAttempt(event.getAttemptCount()));
            recovered++;
        }
        return recovered;
    }
}
