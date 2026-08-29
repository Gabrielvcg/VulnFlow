package com.vulnflow.ui.scan;
import jakarta.persistence.LockModeType; import java.time.Instant; import java.util.*; import org.springframework.data.domain.*; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
public interface UiScanRequestRepository extends JpaRepository<UiScanRequest,UUID>{
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from UiScanRequest r where r.id=:id") Optional<UiScanRequest> findByIdForUpdate(@Param("id")UUID id);
 @Query(value="SELECT id FROM ui_scan_requests WHERE status='REQUESTED' ORDER BY requested_at,id FOR UPDATE SKIP LOCKED LIMIT 1",nativeQuery=true) Optional<UUID> findNextClaimableId();
 long countByStatusIn(Collection<UiScanRequestStatus>s); long countByRequestedByIdAndStatusIn(UUID user,Collection<UiScanRequestStatus>s);
 long countByRequestedByIdAndRequestedAtAfter(UUID user,Instant after); long countByTargetIdAndRequestedAtAfter(UUID target,Instant after);
 List<UiScanRequest> findByStatusInAndClaimExpiresAtBefore(Collection<UiScanRequestStatus>s,Instant cutoff);
 List<UiScanRequest> findByStatusAndRequestedAtBefore(UiScanRequestStatus status,Instant cutoff);
 Page<UiScanRequest> findAllByOrderByRequestedAtDesc(Pageable pageable);
 Optional<UiScanRequest> findByIdAndRequestedById(UUID id,UUID userId);
}
