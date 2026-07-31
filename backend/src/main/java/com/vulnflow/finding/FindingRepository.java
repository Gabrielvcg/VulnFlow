package com.vulnflow.finding;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FindingRepository extends JpaRepository<Finding, UUID> {

    @Query("""
            SELECT finding
            FROM Finding finding
            WHERE (:severity IS NULL OR finding.severity = :severity)
              AND (:status IS NULL OR finding.status = :status)
              AND (:assetId IS NULL OR finding.asset.id = :assetId)
              AND (:knownExploited IS NULL OR finding.knownExploited = :knownExploited)
            """)
    Page<Finding> findFiltered(
            @Param("severity") FindingSeverity severity,
            @Param("status") FindingStatus status,
            @Param("assetId") UUID assetId,
            @Param("knownExploited") Boolean knownExploited,
            Pageable pageable);

    long countBySeverity(FindingSeverity severity);

    long countByStatus(FindingStatus status);

    long countByKnownExploitedTrue();

    long countByScanId(UUID scanId);

    long countByScanIdAndSeverity(UUID scanId, FindingSeverity severity);

    @Modifying
    @Query("DELETE FROM Finding finding WHERE finding.scan.id = :scanId")
    int deleteByScanId(@Param("scanId") UUID scanId);
}
