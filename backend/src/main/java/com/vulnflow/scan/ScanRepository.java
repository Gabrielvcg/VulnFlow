package com.vulnflow.scan;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScanRepository extends JpaRepository<Scan, UUID> {

    Optional<Scan> findByAssetIdAndContentHash(UUID assetId, String contentHash);

    @Modifying
    @Query(
            value = """
                    INSERT INTO scans (
                        id,
                        asset_id,
                        scanner,
                        status,
                        started_at,
                        received_at,
                        source_file_name,
                        content_hash
                    )
                    VALUES (
                        :id,
                        :assetId,
                        'TRIVY',
                        'PROCESSING',
                        :startedAt,
                        :startedAt,
                        :sourceFileName,
                        :contentHash
                    )
                    ON CONFLICT (asset_id, content_hash) DO NOTHING
                    """,
            nativeQuery = true)
    int insertProcessingIfAbsent(
            @Param("id") UUID id,
            @Param("assetId") UUID assetId,
            @Param("sourceFileName") String sourceFileName,
            @Param("contentHash") String contentHash,
            @Param("startedAt") Instant startedAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT scan
            FROM Scan scan
            WHERE scan.asset.id = :assetId
              AND scan.contentHash = :contentHash
            """)
    Optional<Scan> findByAssetIdAndContentHashForUpdate(
            @Param("assetId") UUID assetId,
            @Param("contentHash") String contentHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT scan FROM Scan scan WHERE scan.id = :scanId")
    Optional<Scan> findByIdForUpdate(@Param("scanId") UUID scanId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Scan scan
            SET scan.status = :failedStatus,
                scan.completedAt = :completedAt,
                scan.failureReason = :failureReason
            WHERE scan.status = :processingStatus
              AND scan.startedAt < :cutoff
            """)
    int failStaleProcessing(
            @Param("processingStatus") ScanStatus processingStatus,
            @Param("failedStatus") ScanStatus failedStatus,
            @Param("cutoff") Instant cutoff,
            @Param("completedAt") Instant completedAt,
            @Param("failureReason") String failureReason);
}
