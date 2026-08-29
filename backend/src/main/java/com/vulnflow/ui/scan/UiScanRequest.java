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
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private UiScanRequestStatus status;
    @Column(name="claim_token") private UUID claimToken; @Column(name="claim_expires_at") private Instant claimExpiresAt;
    @Column(name="heartbeat_at") private Instant heartbeatAt; @Column(name="recovery_attempts",nullable=false) private int recoveryAttempts;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="scan_id") private Scan scan;
    @Column(name="event_id") private UUID eventId; @Column(name="safe_error",length=500) private String safeError;
    @Column(name="requested_at",nullable=false) private Instant requestedAt; @Column(name="claimed_at") private Instant claimedAt;
    @Column(name="started_at") private Instant startedAt; @Column(name="uploaded_at") private Instant uploadedAt;
    @Column(name="completed_at") private Instant completedAt; @Column(name="updated_at",nullable=false) private Instant updatedAt;
    protected UiScanRequest() {}
    public UiScanRequest(UiTarget target,UiUser user){id=UUID.randomUUID();this.target=target;requestedBy=user;status=UiScanRequestStatus.REQUESTED;requestedAt=Instant.now();updatedAt=requestedAt;}
    public UUID claim(UiAgent agent,Duration lease){require(UiScanRequestStatus.REQUESTED);Instant now=Instant.now();this.agent=agent;status=UiScanRequestStatus.CLAIMED;claimToken=UUID.randomUUID();claimedAt=now;heartbeatAt=now;claimExpiresAt=now.plus(lease);updatedAt=now;return claimToken;}
    public void heartbeat(UUID token,Duration lease){fence(token);heartbeatAt=Instant.now();claimExpiresAt=heartbeatAt.plus(lease);updatedAt=heartbeatAt;}
    public void start(UUID token,Duration lease){fence(token);require(UiScanRequestStatus.CLAIMED);status=UiScanRequestStatus.RUNNING;startedAt=Instant.now();heartbeatAt=startedAt;claimExpiresAt=startedAt.plus(lease);updatedAt=startedAt;}
    public void uploading(UUID token,Duration lease){fence(token);if(status!=UiScanRequestStatus.RUNNING&&status!=UiScanRequestStatus.UPLOADING)throw new IllegalStateException("Request is not running");status=UiScanRequestStatus.UPLOADING;heartbeat(token,lease);}
    public void processing(UUID token,Scan scan,UUID eventId){fence(token);if(status!=UiScanRequestStatus.UPLOADING&&status!=UiScanRequestStatus.RUNNING)throw new IllegalStateException("Request is not uploading");status=UiScanRequestStatus.PROCESSING;this.scan=scan;this.eventId=eventId;uploadedAt=Instant.now();claimToken=null;claimExpiresAt=null;updatedAt=uploadedAt;}
    public void complete(){if(status!=UiScanRequestStatus.PROCESSING)throw new IllegalStateException("Request is not processing");status=UiScanRequestStatus.COMPLETED;completedAt=Instant.now();updatedAt=completedAt;}
    public void fail(UUID token,String error){if(token!=null)fence(token);status=UiScanRequestStatus.FAILED;safeError=limit(error);claimToken=null;claimExpiresAt=null;completedAt=Instant.now();updatedAt=completedAt;}
    public boolean recover(Duration expiry,int max){if(status==UiScanRequestStatus.REQUESTED&&requestedAt.isBefore(Instant.now().minus(expiry))){fail(null,"Request expired before claim");return false;} if((status==UiScanRequestStatus.CLAIMED||status==UiScanRequestStatus.RUNNING||status==UiScanRequestStatus.UPLOADING)&&claimExpiresAt.isBefore(Instant.now())){if(recoveryAttempts>=max){fail(null,"Agent claim expired");return false;} recoveryAttempts++;status=UiScanRequestStatus.REQUESTED;agent=null;claimToken=null;claimExpiresAt=null;heartbeatAt=null;updatedAt=Instant.now();return true;} return false;}
    private void fence(UUID token){if(token==null||!token.equals(claimToken)||claimExpiresAt==null||claimExpiresAt.isBefore(Instant.now()))throw new StaleScanClaimException();}
    private void require(UiScanRequestStatus expected){if(status!=expected)throw new IllegalStateException("Expected request state "+expected);}
    private String limit(String v){return v==null?null:v.substring(0,Math.min(500,v.length()));}
    public UUID getId(){return id;} public UiTarget getTarget(){return target;} public UiUser getRequestedBy(){return requestedBy;} public UiAgent getAgent(){return agent;}
    public UiScanRequestStatus getStatus(){return status;} public UUID getClaimToken(){return claimToken;} public Instant getClaimExpiresAt(){return claimExpiresAt;}
    public int getRecoveryAttempts(){return recoveryAttempts;} public Scan getScan(){return scan;} public UUID getEventId(){return eventId;} public String getSafeError(){return safeError;}
    public Instant getRequestedAt(){return requestedAt;} public Instant getClaimedAt(){return claimedAt;} public Instant getStartedAt(){return startedAt;} public Instant getUploadedAt(){return uploadedAt;} public Instant getCompletedAt(){return completedAt;}
}
