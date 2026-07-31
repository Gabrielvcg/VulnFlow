package com.vulnflow.ingestion;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {

    Optional<IngestionJob> findByScanId(UUID scanId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT job FROM IngestionJob job WHERE job.id = :jobId")
    Optional<IngestionJob> findByIdForUpdate(@Param("jobId") UUID jobId);

    @Query(value = """
            SELECT id
            FROM ingestion_jobs
            WHERE status IN ('PENDING', 'RETRY_WAIT')
              AND available_at <= :now
              AND attempt_count < max_attempts
            ORDER BY available_at, created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findClaimableIds(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT id
            FROM ingestion_jobs
            WHERE status = 'PROCESSING'
              AND locked_at < :cutoff
            ORDER BY locked_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findStaleProcessingIds(
            @Param("cutoff") Instant cutoff,
            @Param("batchSize") int batchSize);

    @Query("""
            SELECT job
            FROM IngestionJob job
            WHERE (:status IS NULL OR job.status = :status)
              AND (:scanId IS NULL OR job.scan.id = :scanId)
            """)
    Page<IngestionJob> findFiltered(
            @Param("status") IngestionJobStatus status,
            @Param("scanId") UUID scanId,
            Pageable pageable);

    long countByStatus(IngestionJobStatus status);
}
