package com.vulnflow.aws.ingestion;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AwsPublicationOutboxRepository extends JpaRepository<AwsPublicationOutbox, UUID> {
    long countByStatus(AwsPublicationStatus status);
    Optional<AwsPublicationOutbox> findByScanId(UUID scanId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT event FROM AwsPublicationOutbox event WHERE event.eventId = :eventId")
    Optional<AwsPublicationOutbox> findByEventIdForUpdate(@Param("eventId") UUID eventId);

    @Query(value = """
            SELECT event_id
            FROM aws_publication_outbox
            WHERE status = 'PUBLISH_PENDING'
              AND available_at <= :now
              AND attempt_count < max_attempts
            ORDER BY available_at, created_at, event_id
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findClaimableIds(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT event_id
            FROM aws_publication_outbox
            WHERE status = 'PUBLISHING'
              AND locked_at < :cutoff
            ORDER BY locked_at, event_id
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findStaleIds(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
