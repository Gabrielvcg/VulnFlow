package com.vulnflow.aws.ingestion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("aws")
public class AwsOutboxClaimService {
    private final AwsPublicationOutboxRepository repository;

    public AwsOutboxClaimService(AwsPublicationOutboxRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AwsPublicationClaim> claimAvailable(int batchSize) {
        Instant now = Instant.now();
        List<UUID> ids = repository.findClaimableIds(now, batchSize);
        List<AwsPublicationClaim> claims = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            AwsPublicationOutbox event = repository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("A claimed outbox event disappeared"));
            UUID token = event.claim(now);
            claims.add(new AwsPublicationClaim(
                    event.getEventId(),
                    event.getPayloadKey(),
                    event.getEventJson(),
                    token,
                    event.getAttemptCount()));
        }
        return claims;
    }
}
