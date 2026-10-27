package com.vulnflow.ui.scan;

import com.vulnflow.scan.Scan;
import com.vulnflow.ui.auth.UiUser;
import com.vulnflow.ui.target.UiTarget;
import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="ui_scan_requests")
public class UiScanRequest {
    @Id private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="target_id") private UiTarget target;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="requested_by") private UiUser requestedBy;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="agent_id") private UiAgent agent;
    @Column(name="requested_agent_id",length=100) private String requestedAgentId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private UiScanRequestStatus status;
    @Column(name="claim_token") private UUID claimToken; @Column(name="claim_expires_at") private Instant claimExpiresAt;
    @Column(name="heartbeat_at") private Instant heartbeatAt; @Column(name="recovery_attempts",nullable=false) private int recoveryAttempts;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="scan_id") private Scan scan;
    @Column(name="event_id") private UUID eventId; @Column(name="safe_error",length=500) private String safeError;
    @Column(name="requested_at",nullable=false) private Instant requestedAt; @Column(name="claimed_at") private Instant claimedAt;
    @Column(name="started_at") private Instant startedAt; @Column(name="uploaded_at") private Instant uploadedAt;
    @Column(name="completed_at") private Instant completedAt; @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Column(name="upload_asset_id") private UUID uploadAssetId;
    @Column(name="upload_content_hash",length=64) private String uploadContentHash;
    @Column(name="upload_attempt_id") private UUID uploadAttemptId;
    protected UiScanRequest() {}

    public UploadReservation reserveUpload(UUID token, UUID assetId, String contentHash, Duration lease) {
        if (scan != null) {
            acceptedUpload(token, assetId, contentHash);
            return new UploadReservation(scan.getId(), null);
        }
        fence(token);
        if (uploadAssetId == null) {
            if (target.getAsset() == null || !target.getAsset().getId().equals(assetId)) {
                throw new StaleScanClaimException();
            }
        } else if (!uploadAssetId.equals(assetId) || !uploadContentHash.equals(contentHash)) {
            throw new StaleScanClaimException();
        }
        if (uploadAttemptId != null) {
            // The existing generic 500 response remains retryable for the agent; a 409 is permanent.
            throw new IllegalStateException("The command upload is still in progress");
        }
        uploading(token, lease);
        uploadAssetId = assetId;
        uploadContentHash = contentHash;
        uploadAttemptId = UUID.randomUUID();
        return new UploadReservation(null, uploadAttemptId);
    }

    public void associateUpload(UUID token, UUID attemptId, Scan scan, UUID eventId) {
        if (this.scan != null) {
            acknowledgeAssociation(token, scan, eventId);
            return;
        }
        fence(token);
        if (attemptId == null || !attemptId.equals(uploadAttemptId)
                || !scan.getAsset().getId().equals(uploadAssetId)
                || !scan.getContentHash().equals(uploadContentHash)) {
            throw new StaleScanClaimException();
        }
        processing(token, scan, eventId);
        uploadAttemptId = null;
    }

    public void acknowledgeAssociation(UUID token, Scan scan, UUID eventId) {
        acceptedUpload(token, scan.getAsset().getId(), scan.getContentHash());
        if (!this.scan.getId().equals(scan.getId()) || !java.util.Objects.equals(this.eventId, eventId)) {
            throw new StaleScanClaimException();
        }
    }

    public void releaseUpload(UUID token, UUID attemptId) {
        if (scan == null && status == UiScanRequestStatus.UPLOADING && token != null
                && token.equals(claimToken) && attemptId != null && attemptId.equals(uploadAttemptId)
                && claimExpiresAt != null && claimExpiresAt.isAfter(Instant.now())) {
            // Release only this execution slot, retaining its pinned content identity after uncertain results.
            uploadAttemptId = null;
            status = UiScanRequestStatus.RUNNING;
            updatedAt = Instant.now();
        }
    }

    private void acceptedUpload(UUID token, UUID assetId, String contentHash) {
        if (scan == null || uploadedAt == null || token == null || !token.equals(claimToken)
                || claimExpiresAt != null
                || (status != UiScanRequestStatus.PROCESSING && status != UiScanRequestStatus.COMPLETED
                    && status != UiScanRequestStatus.FAILED)
                || !scan.getAsset().getId().equals(assetId) || !scan.getContentHash().equals(contentHash)) {
            throw new StaleScanClaimException();
        }
    }

    public record UploadReservation(UUID acceptedScanId, UUID attemptId) {}
    public void routeTo(String agentId) { requestedAgentId = agentId; }
    public String getRequestedAgentId() { return requestedAgentId; }
    public UiScanRequest(UiTarget target,UiUser user){id=UUID.randomUUID();this.target=target;requestedBy=user;status=UiScanRequestStatus.REQUESTED;requestedAt=Instant.now();updatedAt=requestedAt;}
    public UUID claim(UiAgent agent,Duration lease){require(UiScanRequestStatus.REQUESTED);Instant now=Instant.now();this.agent=agent;uploadAssetId=null;uploadContentHash=null;uploadAttemptId=null;status=UiScanRequestStatus.CLAIMED;claimToken=UUID.randomUUID();claimedAt=now;heartbeatAt=now;claimExpiresAt=now.plus(lease);updatedAt=now;return claimToken;}
    public void heartbeat(UUID token,Duration lease){fence(token);heartbeatAt=Instant.now();claimExpiresAt=heartbeatAt.plus(lease);updatedAt=heartbeatAt;}
    public void start(UUID token,Duration lease){fence(token);require(UiScanRequestStatus.CLAIMED);status=UiScanRequestStatus.RUNNING;startedAt=Instant.now();heartbeatAt=startedAt;claimExpiresAt=startedAt.plus(lease);updatedAt=startedAt;}
    public void uploading(UUID token,Duration lease){fence(token);if(status!=UiScanRequestStatus.RUNNING&&status!=UiScanRequestStatus.UPLOADING)throw new IllegalStateException("Request is not running");status=UiScanRequestStatus.UPLOADING;heartbeat(token,lease);}
    public void processing(UUID token,Scan scan,UUID eventId){fence(token);if(status!=UiScanRequestStatus.UPLOADING&&status!=UiScanRequestStatus.RUNNING)throw new IllegalStateException("Request is not uploading");status=UiScanRequestStatus.PROCESSING;this.scan=scan;this.eventId=eventId;uploadedAt=Instant.now();claimExpiresAt=null;updatedAt=uploadedAt;}
    public void complete(){if(status!=UiScanRequestStatus.PROCESSING)throw new IllegalStateException("Request is not processing");status=UiScanRequestStatus.COMPLETED;completedAt=Instant.now();updatedAt=completedAt;}
    public void fail(UUID token,String error){if(token!=null)fence(token);status=UiScanRequestStatus.FAILED;safeError=limit(error);if(scan==null)claimToken=null;claimExpiresAt=null;completedAt=Instant.now();updatedAt=completedAt;}
    public boolean recover(Duration expiry,int max){if(status==UiScanRequestStatus.REQUESTED&&requestedAt.isBefore(Instant.now().minus(expiry))){fail(null,"Request expired before claim");return false;} if((status==UiScanRequestStatus.CLAIMED||status==UiScanRequestStatus.RUNNING||status==UiScanRequestStatus.UPLOADING)&&claimExpiresAt.isBefore(Instant.now())){if(recoveryAttempts>=max){fail(null,"Agent claim expired");return false;} recoveryAttempts++;status=UiScanRequestStatus.REQUESTED;agent=null;claimToken=null;claimExpiresAt=null;uploadAssetId=null;uploadContentHash=null;uploadAttemptId=null;heartbeatAt=null;updatedAt=Instant.now();return true;} return false;}
    private void fence(UUID token){if(token==null||!token.equals(claimToken)||claimExpiresAt==null||claimExpiresAt.isBefore(Instant.now()))throw new StaleScanClaimException();}
    private void require(UiScanRequestStatus expected){if(status!=expected)throw new IllegalStateException("Expected request state "+expected);}
    private String limit(String v){return v==null?null:v.substring(0,Math.min(500,v.length()));}
    public UUID getId(){return id;} public UiTarget getTarget(){return target;} public UiUser getRequestedBy(){return requestedBy;} public UiAgent getAgent(){return agent;}
    public UiScanRequestStatus getStatus(){return status;} public UUID getClaimToken(){return claimToken;} public Instant getClaimExpiresAt(){return claimExpiresAt;}
    public int getRecoveryAttempts(){return recoveryAttempts;} public Scan getScan(){return scan;} public UUID getEventId(){return eventId;} public String getSafeError(){return safeError;}
    public Instant getRequestedAt(){return requestedAt;} public Instant getClaimedAt(){return claimedAt;} public Instant getStartedAt(){return startedAt;} public Instant getUploadedAt(){return uploadedAt;} public Instant getCompletedAt(){return completedAt;}
}
