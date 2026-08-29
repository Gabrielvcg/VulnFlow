package com.vulnflow.ui.query;

import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetRepository;
import com.vulnflow.aws.ingestion.AwsPublicationOutboxRepository;
import com.vulnflow.aws.ingestion.AwsPublicationStatus;
import com.vulnflow.finding.Finding;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.FindingSeverity;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScanResponse;
import com.vulnflow.shared.exception.ResourceNotFoundException;
import com.vulnflow.ui.UiProperties;
import com.vulnflow.config.AwsIngestionProperties;
import com.vulnflow.ui.scan.UiAgent;
import com.vulnflow.ui.scan.UiAgentRepository;
import com.vulnflow.ui.scan.UiScanRequestService;
import com.vulnflow.ui.auth.UiPrincipal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import com.vulnflow.processing.port.ProcessingResultReader;
import com.vulnflow.processing.port.ProcessingFindingResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController @RequestMapping("/api/ui/v1")
public class UiQueryController {
    private final AssetRepository assets; private final ScanRepository scans; private final FindingRepository findings;
    private final UiAgentRepository agents; private final UiScanRequestService scanRequests; private final AwsPublicationOutboxRepository outbox; private final UiProperties properties; private final Environment environment; private final ObjectProvider<SqsClient> sqs; private final ObjectProvider<AwsIngestionProperties> aws; private final ObjectProvider<ProcessingResultReader> resultReaders; private final String dlqUrl;
    public UiQueryController(AssetRepository assets,ScanRepository scans,FindingRepository findings,UiAgentRepository agents,UiScanRequestService scanRequests,AwsPublicationOutboxRepository outbox,UiProperties properties,Environment environment,ObjectProvider<SqsClient> sqs,ObjectProvider<AwsIngestionProperties> aws,ObjectProvider<ProcessingResultReader> resultReaders,@Value("${VULNFLOW_SQS_DLQ_URL:}")String dlqUrl){this.assets=assets;this.scans=scans;this.findings=findings;this.agents=agents;this.scanRequests=scanRequests;this.outbox=outbox;this.properties=properties;this.environment=environment;this.sqs=sqs;this.aws=aws;this.resultReaders=resultReaders;this.dlqUrl=dlqUrl;}

    @GetMapping("/dashboard") public Dashboard dashboard(){Instant since=Instant.now().minusSeconds(30L*86400);List<Scan> recent=scans.findByReceivedAtAfterOrderByReceivedAtDesc(since,PageRequest.of(0,500)).getContent();List<UUID> scanIds=recent.stream().map(Scan::getId).toList();Map<String,Long> severity=new java.util.LinkedHashMap<>();long findingCount;ProcessingResultReader reader=resultReaders.getIfAvailable();if(reader==null){for(FindingSeverity s:FindingSeverity.values())severity.put(s.name(),scanIds.isEmpty()?0:findings.countByScanIdInAndSeverity(scanIds,s));findingCount=scanIds.isEmpty()?0:findings.countByScanIdIn(scanIds);}else{Map<UUID,com.vulnflow.processing.port.ProcessingResultSummary> summaries=reader.findScans(scanIds);for(FindingSeverity s:FindingSeverity.values())severity.put(s.name(),summaries.values().stream().mapToLong(summary->summary.severitySummary().getOrDefault(s.name(),0)).sum());findingCount=summaries.values().stream().mapToLong(com.vulnflow.processing.port.ProcessingResultSummary::findingCount).sum();}long assetCount=recent.stream().map(scan->scan.getAsset().getId()).distinct().count();List<UiAgent> agentList=agents.findAllByOrderByLastHeartbeatAtDesc();UiAgent agent=agentList.isEmpty()?null:agentList.get(0);return new Dashboard(since,500,recent.size(),assetCount,findingCount,severity,agent==null?null:AgentView.from(agent,properties.agentOfflineAfter()));}
    @GetMapping("/assets") public Page<AssetView> assets(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){return this.assets.findAll(PageRequest.of(page,Math.min(size,100))).map(AssetView::from);}
    @GetMapping("/assets/{id}/scans") public Page<ScanResponse> assetScans(@PathVariable UUID id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){if(!assets.existsById(id))throw new ResourceNotFoundException("Asset",id);return scans.findByAssetIdOrderByReceivedAtDesc(id,PageRequest.of(page,Math.min(size,100))).map(ScanResponse::from);}
    @GetMapping("/scan-requests/{id}/findings") public FindingsPage requestFindings(@PathVariable UUID id,@AuthenticationPrincipal UiPrincipal principal,@RequestParam(defaultValue="0")int page,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="25")int size){UUID scanId=scanRequests.authorizeResultAccess(id,principal);int bounded=Math.min(size,100);Page<Finding> local=findings.findByScanId(scanId,PageRequest.of(page,bounded));if(local.hasContent()||resultReaders.getIfAvailable()==null)return new FindingsPage(local.map(FindingView::from).getContent(),null,local.getNumber(),local.getTotalPages(),local.getTotalElements());var awsPage=resultReaders.getIfAvailable().findFindings(scanId,cursor,bounded);return new FindingsPage(awsPage.findings().stream().map(FindingView::from).toList(),awsPage.nextCursor(),0,awsPage.nextCursor()==null?1:2,awsPage.findings().size());}
    @GetMapping("/operations") public Operations operations(){UiAgent agent=agents.findAllByOrderByLastHeartbeatAtDesc().stream().findFirst().orElse(null);Map<String,Long> publication=Map.of("pending",outbox.countByStatus(AwsPublicationStatus.PUBLISH_PENDING),"publishing",outbox.countByStatus(AwsPublicationStatus.PUBLISHING),"published",outbox.countByStatus(AwsPublicationStatus.PUBLISHED),"failed",outbox.countByStatus(AwsPublicationStatus.FAILED));return new Operations(List.of(environment.getActiveProfiles()),agent==null?null:AgentView.from(agent,properties.agentOfflineAfter()),publication,queueTelemetry(),properties.sqsTelemetryEnabled(),properties.scansEnabled());}
    private QueueTelemetry queueTelemetry(){if(!properties.sqsTelemetryEnabled())return null;SqsClient client=sqs.getIfAvailable();AwsIngestionProperties config=aws.getIfAvailable();if(client==null||config==null||dlqUrl.isBlank())return new QueueTelemetry(null,null,"unavailable");try{return new QueueTelemetry(attributes(client,config.sqsQueueUrl()),attributes(client,dlqUrl),"healthy");}catch(RuntimeException exception){return new QueueTelemetry(null,null,"degraded");}}
    private QueueCounts attributes(SqsClient client,String url){var values=client.getQueueAttributes(r->r.queueUrl(url).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();return new QueueCounts(parse(values.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)),parse(values.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)));}
    private long parse(String value){try{return Long.parseLong(value);}catch(RuntimeException exception){return 0;}}
    public record Dashboard(Instant since,int scanLimit,long scans,long assets,long findings,Map<String,Long> severity,AgentView agent){}
    public record AssetView(UUID id,String name,String type,Instant updatedAt){static AssetView from(Asset a){return new AssetView(a.getId(),a.getName(),a.getType().name(),a.getUpdatedAt());}}
    public record FindingView(String id,String vulnerabilityId,String packageName,String installedVersion,String fixedVersion,String severity,String title,int riskScore,boolean knownExploited){static FindingView from(Finding f){return new FindingView(f.getId().toString(),f.getVulnerabilityId(),f.getPackageName(),f.getInstalledVersion(),f.getFixedVersion(),f.getSeverity().name(),f.getTitle(),f.getRiskScore(),f.isKnownExploited());}static FindingView from(ProcessingFindingResult f){return new FindingView(f.findingKey(),f.vulnerabilityId(),f.packageName(),f.installedVersion(),f.fixedVersion(),f.severity().name(),f.title(),f.riskScore(),f.knownExploited());}}
    public record FindingsPage(List<FindingView> content,String nextCursor,int number,int totalPages,long totalElements){}
    public record AgentView(String id,String status,boolean online,Instant lastHeartbeatAt,int outboxPending,int deadLetters,long outboxBytes,long diskFreeBytes,String safeError){static AgentView from(UiAgent a,java.time.Duration offline){return new AgentView(a.getId(),a.getStatus(),a.getLastHeartbeatAt().isAfter(Instant.now().minus(offline)),a.getLastHeartbeatAt(),a.getOutboxPending(),a.getOutboxDeadLetters(),a.getOutboxBytes(),a.getDiskFreeBytes(),a.getLastError());}}
    public record QueueCounts(long visible,long inFlight){} public record QueueTelemetry(QueueCounts source,QueueCounts dlq,String status){}
    public record Operations(List<String> activeProfiles,AgentView agent,Map<String,Long> publicationOutbox,QueueTelemetry queues,boolean sqsTelemetryEnabled,boolean scansEnabled){}
}
