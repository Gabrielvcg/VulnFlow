package com.vulnflow.ui.scan;

import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScanStatus;
import com.vulnflow.shared.exception.ResourceNotFoundException;
import com.vulnflow.ui.UiProperties;
import com.vulnflow.ui.audit.UiAuditService;
import com.vulnflow.ui.auth.UiPrincipal;
import com.vulnflow.ui.auth.UiRole;
import com.vulnflow.ui.auth.UiUser;
import com.vulnflow.ui.auth.UiUserRepository;
import com.vulnflow.ui.target.UiTarget;
import com.vulnflow.ui.target.UiTargetRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import com.vulnflow.processing.port.ProcessingResultReader;
import com.vulnflow.processing.port.ProcessingResultStatus;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class UiScanRequestService {
    private static final Logger LOGGER=LoggerFactory.getLogger(UiScanRequestService.class);
    private static final EnumSet<UiScanRequestStatus> ACTIVE = EnumSet.of(UiScanRequestStatus.REQUESTED,UiScanRequestStatus.CLAIMED,UiScanRequestStatus.RUNNING,UiScanRequestStatus.UPLOADING,UiScanRequestStatus.PROCESSING);
    private static final EnumSet<UiScanRequestStatus> LEASED = EnumSet.of(UiScanRequestStatus.CLAIMED,UiScanRequestStatus.RUNNING,UiScanRequestStatus.UPLOADING);
    private final UiScanRequestRepository requests; private final UiTargetRepository targets; private final UiUserRepository users;
    private final UiAgentRepository agents; private final ScanRepository scans; private final UiProperties properties; private final UiAuditService audit; private final ObjectProvider<ProcessingResultReader> resultReaders;
    public UiScanRequestService(UiScanRequestRepository requests,UiTargetRepository targets,UiUserRepository users,UiAgentRepository agents,ScanRepository scans,UiProperties properties,UiAuditService audit,ObjectProvider<ProcessingResultReader> resultReaders){this.requests=requests;this.targets=targets;this.users=users;this.agents=agents;this.scans=scans;this.properties=properties;this.audit=audit;this.resultReaders=resultReaders;}

    @Transactional
    public ScanRequestResponse create(UUID targetId,UiPrincipal principal){
        if(!properties.scansEnabled())reject("SCANS_DISABLED","On-demand scans are disabled");
        UiTarget target=targets.findById(targetId).filter(UiTarget::isEnabled).orElseThrow(()->new ResourceNotFoundException("Target",targetId));
        UiUser user=users.getReferenceById(principal.id()); Instant now=Instant.now();
        if(requests.countByRequestedByIdAndStatusIn(principal.id(),ACTIVE)>0)reject("USER_SCAN_ACTIVE","The user already has an active scan");
        if(requests.countByStatusIn(ACTIVE)>=properties.queueCapacity())reject("QUEUE_FULL","The scan queue is full");
        if(requests.countByRequestedByIdAndRequestedAtAfter(principal.id(),now.minusSeconds(3600))>=properties.maxHourlyPerUser())reject("HOURLY_QUOTA","Hourly scan quota reached");
        if(requests.countByRequestedByIdAndRequestedAtAfter(principal.id(),now.minusSeconds(86400))>=properties.maxDailyPerUser())reject("DAILY_QUOTA","Daily scan quota reached");
        if(requests.countByTargetIdAndRequestedAtAfter(targetId,now.minus(properties.targetCooldown()))>0)reject("TARGET_COOLDOWN","Target cooldown is active");
        UiAgent agent=agents.findAllByOrderByLastHeartbeatAtDesc().stream().findFirst().orElse(null);
        if(agent==null||agent.getLastHeartbeatAt().isBefore(now.minus(properties.agentOfflineAfter())))reject("AGENT_OFFLINE","The scan agent is offline");
        if(agent.getDiskFreeBytes()<properties.agentMinFreeBytes())reject("AGENT_DISK_LOW","The scan agent has insufficient free disk");
        if(agent.getOutboxBytes()>=1_073_741_824L)reject("OUTBOX_FULL","The agent outbox is full");
        UiScanRequest created=requests.save(new UiScanRequest(target,user));
        audit.record(user,principal.username(),"SCAN_REQUESTED","SCAN_REQUEST",created.getId().toString(),"SUCCESS",null,"Approved catalog target requested");
        LOGGER.info("Se registró una solicitud de escaneo permitida: requestId={}",created.getId());
        return response(created);
    }

    @Transactional
    public AgentClaim claim(String agentId,Heartbeat body){
        heartbeat(agentId,body);
        if(!properties.scansEnabled())return null;
        recoverExpired();
        UUID id=requests.findNextClaimableId().orElse(null); if(id==null)return null;
        UiScanRequest request=requests.findByIdForUpdate(id).orElseThrow(); UiAgent agent=agents.getReferenceById(agentId);
        UUID token=request.claim(agent,properties.claimLease());
        return new AgentClaim(request.getId(),token,request.getClaimExpiresAt(),request.getTarget().getName(),request.getTarget().getType().name(),request.getTarget().getExternalReference());
    }

    @Transactional
    public void heartbeat(String agentId,Heartbeat body){UiAgent agent=agents.findById(agentId).orElseGet(()->new UiAgent(agentId));agent.heartbeat(body.status(),body.currentRequestId(),body.outboxPending(),body.outboxDeadLetters(),body.outboxBytes(),body.diskFreeBytes(),body.safeError());agents.save(agent);if(body.currentRequestId()!=null&&body.claimToken()!=null){requests.findByIdForUpdate(body.currentRequestId()).ifPresent(r->r.heartbeat(body.claimToken(),properties.claimLease()));}}
    @Transactional public void start(String agentId,UUID id,UUID token){UiScanRequest r=claimed(agentId,id);r.start(token,properties.claimLease());}
    @Transactional public void fail(String agentId,UUID id,UUID token,String error){UiScanRequest r=claimed(agentId,id);r.fail(token,error);}
    @Transactional public void verifyUpload(UUID id,UUID token){UiScanRequest r=requests.findByIdForUpdate(id).orElseThrow(()->new ResourceNotFoundException("Scan request",id));r.uploading(token,properties.claimLease());}
    @Transactional public void associateUpload(UUID id,UUID token,UUID scanId,UUID eventId){UiScanRequest r=requests.findByIdForUpdate(id).orElseThrow();Scan scan=scans.findById(scanId).orElseThrow();r.processing(token,scan,eventId);}
    @Transactional public void completeFromLocalScan(UiScanRequest r){if(r.getStatus()==UiScanRequestStatus.PROCESSING&&r.getScan()!=null){if(r.getScan().getStatus()==ScanStatus.COMPLETED){r.complete();return;}if(r.getScan().getStatus()==ScanStatus.FAILED){r.fail(null,r.getScan().getFailureReason());return;}ProcessingResultReader reader=resultReaders.getIfAvailable();if(reader!=null)reader.findScan(r.getScan().getId()).ifPresent(result->{if(result.status()==ProcessingResultStatus.COMPLETED)r.complete();else if(result.status()==ProcessingResultStatus.FAILED)r.fail(null,result.safeError());});}}

    @Transactional(readOnly=true) public Page<ScanRequestResponse> list(Pageable pageable){return requests.findAllByOrderByRequestedAtDesc(pageable).map(this::responseProjected);}
    @Transactional public ScanRequestResponse get(UUID id,UiPrincipal principal){UiScanRequest r=principal.role()==UiRole.ADMIN?requests.findById(id).orElseThrow(()->new ResourceNotFoundException("Scan request",id)):requests.findByIdAndRequestedById(id,principal.id()).orElseThrow(()->new ResourceNotFoundException("Scan request",id));completeFromLocalScan(r);return response(r);}
    @Transactional(readOnly=true)
    public UUID authorizeResultAccess(UUID requestId,UiPrincipal principal){
        UiScanRequest request=principal.role()==UiRole.ADMIN
                ? requests.findById(requestId).orElseThrow(()->new ResourceNotFoundException("Scan request",requestId))
                : requests.findByIdAndRequestedById(requestId,principal.id()).orElseThrow(()->new ResourceNotFoundException("Scan request",requestId));
        if(request.getScan()==null)throw new ResourceNotFoundException("Scan result",requestId);
        return request.getScan().getId();
    }
    private UiScanRequest claimed(String agentId,UUID id){UiScanRequest r=requests.findByIdForUpdate(id).orElseThrow(()->new ResourceNotFoundException("Scan request",id));if(r.getAgent()==null||!agentId.equals(r.getAgent().getId()))throw new StaleScanClaimException();return r;}
    private void recoverExpired(){Instant now=Instant.now();requests.findByStatusAndRequestedAtBefore(UiScanRequestStatus.REQUESTED,now.minus(properties.requestExpiry())).forEach(r->r.recover(properties.requestExpiry(),properties.maxRecoveryAttempts()));requests.findByStatusInAndClaimExpiresAtBefore(LEASED,now).forEach(r->r.recover(properties.requestExpiry(),properties.maxRecoveryAttempts()));}
    private void reject(String code,String message){throw new ScanRequestRejectedException(code,message);}
    private ScanRequestResponse responseProjected(UiScanRequest r){return response(r);}
    private ScanRequestResponse response(UiScanRequest r){Scan scan=r.getScan();return new ScanRequestResponse(r.getId(),r.getTarget().getId(),r.getTarget().getName(),r.getRequestedBy().getUsername(),r.getAgent()==null?null:r.getAgent().getId(),r.getStatus(),r.getRecoveryAttempts(),scan==null?null:scan.getId(),r.getEventId(),scan==null?null:scan.getContentHash(),scan==null?null:scan.getScanner().name(),r.getSafeError(),r.getRequestedAt(),r.getClaimedAt(),r.getStartedAt(),r.getUploadedAt(),r.getCompletedAt());}
    public record Heartbeat(String status,UUID currentRequestId,UUID claimToken,int outboxPending,int outboxDeadLetters,long outboxBytes,long diskFreeBytes,String safeError){public Heartbeat{if(status==null||!List.of("IDLE","BUSY","DEGRADED").contains(status))status="DEGRADED";}}
    public record AgentClaim(UUID requestId,UUID claimToken,Instant leaseExpiresAt,String targetName,String targetType,String targetReference){}
    public record ScanRequestResponse(UUID id,UUID targetId,String targetName,String requestedBy,String agentId,UiScanRequestStatus status,int recoveryAttempts,UUID scanId,UUID eventId,String contentHash,String scanner,String safeError,Instant requestedAt,Instant claimedAt,Instant startedAt,Instant uploadedAt,Instant completedAt){}
}
