package com.vulnflow.agent.scheduling;

import com.vulnflow.agent.client.AgentClaim;
import com.vulnflow.agent.client.AgentHeartbeat;
import com.vulnflow.agent.client.VulnFlowClient;
import com.vulnflow.agent.outbox.AgentOutbox;
import com.vulnflow.agent.outbox.OutboxItem;
import com.vulnflow.agent.outbox.OutboxStatus;
import com.vulnflow.agent.scanner.ScanArtifact;
import com.vulnflow.agent.scanner.VulnerabilityScanner;
import com.vulnflow.agent.shared.SafeErrors;
import com.vulnflow.agent.target.ScanTarget;
import com.vulnflow.agent.target.TargetType;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandCoordinator {
    private static final Logger LOGGER=LoggerFactory.getLogger(CommandCoordinator.class);
    private final String agentId; private final boolean commandsEnabled; private final Path dataDirectory;
    private final VulnFlowClient client; private final VulnerabilityScanner scanner; private final AgentOutbox outbox; private final ExecutorService executor;
    public CommandCoordinator(String agentId,boolean commandsEnabled,Path dataDirectory,VulnFlowClient client,VulnerabilityScanner scanner,AgentOutbox outbox,ExecutorService executor){this.agentId=agentId;this.commandsEnabled=commandsEnabled;this.dataDirectory=dataDirectory;this.client=client;this.scanner=scanner;this.outbox=outbox;this.executor=executor;}
    public void runCycle(){
        try {
            Optional<OutboxItem> active=outbox.list().stream().filter(i->i.scanRequestId()!=null&&i.status()!=OutboxStatus.UPLOADED).findFirst();
            if(active.isPresent()){OutboxItem item=active.get();if(item.status()==OutboxStatus.DEAD_LETTER){client.failScan(agentId,item.scanRequestId(),item.claimToken(),item.lastError());}else{client.heartbeat(agentId,heartbeat("BUSY",item));}return;}
            AgentHeartbeat idle=heartbeat("IDLE",null);
            if(!commandsEnabled){client.heartbeat(agentId,idle);return;}
            AgentClaim claim=client.claimScan(agentId,idle);if(claim==null)return;
            client.startScan(agentId,claim.requestId(),claim.claimToken());
            ScanTarget target=new ScanTarget(claim.targetName(),TargetType.valueOf(claim.targetType()),claim.targetReference());
            try(ScanArtifact artifact=executor.submit(()->scanner.scan(target)).get()){
                outbox.enqueue(agentId,target,artifact.scannedAt(),artifact.path(),claim.requestId(),claim.claimToken());
                LOGGER.info("event=command_scan_completed agentId={} requestId={} target={} result=stored",agentId,claim.requestId(),target.name());
            } catch(InterruptedException exception){Thread.currentThread().interrupt();client.failScan(agentId,claim.requestId(),claim.claimToken(),"Scan interrupted");}
            catch(ExecutionException|java.io.IOException|RuntimeException exception){client.failScan(agentId,claim.requestId(),claim.claimToken(),SafeErrors.limited("Scan execution failed"));LOGGER.error("event=command_scan_failed agentId={} requestId={} result=reported errorType={}",agentId,claim.requestId(),exception.getClass().getSimpleName());}
        } catch(RuntimeException exception){LOGGER.warn("event=command_cycle_failed agentId={} result=isolated errorType={}",agentId,exception.getClass().getSimpleName());}
    }
    private AgentHeartbeat heartbeat(String status,OutboxItem active){var stats=outbox.stats();long bytes=outbox.list().stream().filter(i->i.status()!=OutboxStatus.UPLOADED).mapToLong(OutboxItem::sizeBytes).sum();long free=0;try{FileStore store=Files.getFileStore(dataDirectory);free=store.getUsableSpace();}catch(java.io.IOException ignored){status="DEGRADED";}return new AgentHeartbeat(status,active==null?null:active.scanRequestId(),active==null?null:active.claimToken(),(int)(stats.pending()+stats.retrying()+stats.uploading()),(int)stats.deadLetters(),bytes,free,null);}
}
