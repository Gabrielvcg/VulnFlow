package com.vulnflow.ui.scan;
import jakarta.persistence.LockModeType; import java.time.Instant; import java.util.*; import org.springframework.data.domain.*; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
public interface UiScanRequestRepository extends JpaRepository<UiScanRequest,UUID>{
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from UiScanRequest r where r.id=:id") Optional<UiScanRequest> findByIdForUpdate(@Param("id")UUID id);
 @Query(value="SELECT id FROM ui_scan_requests WHERE status='REQUESTED' ORDER BY requested_at,id FOR UPDATE SKIP LOCKED LIMIT 1",nativeQuery=true) Optional<UUID> findNextClaimableId();
 long countByStatusIn(Collection<UiScanRequestStatus>s); long countByRequestedByIdAndStatusIn(UUID user,Collection<UiScanRequestStatus>s);
 long countByRequestedByIdAndRequestedAtAfter(UUID user,Instant after); long countByTargetIdAndRequestedAtAfter(UUID target,Instant after);
 List<UiScanRequest> findByStatusInAndClaimExpiresAtBefore(Collection<UiScanRequestStatus>s,Instant cutoff);
 List<UiScanRequest> findByStatusAndRequestedAtBefore(UiScanRequestStatus status,Instant cutoff);
 @Query("""
        SELECT r FROM UiScanRequest r
        LEFT JOIN r.target.asset asset
        WHERE (:userId IS NULL OR r.requestedBy.id = :userId)
          AND (:status IS NULL OR r.status = :status)
          AND (:targetId IS NULL OR r.target.id = :targetId)
          AND (:assetId IS NULL OR asset.id = :assetId)
        ORDER BY r.requestedAt DESC
        """)
 Page<UiScanRequest> findFiltered(@Param("userId")UUID userId,@Param("status")UiScanRequestStatus status,@Param("targetId")UUID targetId,@Param("assetId")UUID assetId,Pageable pageable);
 Optional<UiScanRequest> findByIdAndRequestedById(UUID id,UUID userId);
}
