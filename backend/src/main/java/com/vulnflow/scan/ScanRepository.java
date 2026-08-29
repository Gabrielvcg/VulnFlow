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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ScanRepository extends JpaRepository<Scan, UUID> {

    Page<Scan> findByAssetIdOrderByReceivedAtDesc(UUID assetId, Pageable pageable);

    Page<Scan> findByReceivedAtAfterOrderByReceivedAtDesc(Instant after, Pageable pageable);

    long countByReceivedAtAfter(Instant after);

    Optional<Scan> findByAssetIdAndContentHash(UUID assetId, String contentHash);

    @Modifying
    @Query(
            value = """
                    INSERT INTO scans (
                        id,
                        asset_id,
                        scanner,
                        status,
                        received_at,
                        source_file_name,
                        content_hash
                    )
                    VALUES (
                        :id,
                        :assetId,
                        'TRIVY',
                        'RECEIVED',
                        :receivedAt,
                        :sourceFileName,
                        :contentHash
                    )
                    ON CONFLICT (asset_id, content_hash) DO NOTHING
                    """,
            nativeQuery = true)
    int insertReceivedIfAbsent(
            @Param("id") UUID id,
            @Param("assetId") UUID assetId,
            @Param("sourceFileName") String sourceFileName,
            @Param("contentHash") String contentHash,
            @Param("receivedAt") Instant receivedAt);

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
}
