package com.vulnflow.ui.query;

import com.vulnflow.asset.*;
import com.vulnflow.aws.ingestion.*;
import com.vulnflow.config.AwsIngestionProperties;
import com.vulnflow.finding.*;
import com.vulnflow.processing.port.*;
import com.vulnflow.scan.*;
import com.vulnflow.shared.exception.ResourceNotFoundException;
import com.vulnflow.ui.UiProperties;
import com.vulnflow.ui.auth.UiPrincipal;
import com.vulnflow.ui.scan.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

@RestController
@RequestMapping("/api/ui/v1")
public class UiQueryController {
    private static final int MAX_AWS_SEARCH_PAGES = 20;
    private final AssetRepository assets; private final ScanRepository scans; private final FindingRepository findings;
    private final UiAgentRepository agents; private final UiScanRequestService scanRequests;
    private final AwsPublicationOutboxRepository outbox; private final UiProperties properties;
    private final Environment environment; private final ObjectProvider<SqsClient> sqs;
    private final ObjectProvider<AwsIngestionProperties> aws;
    private final ObjectProvider<ProcessingResultReader> resultReaders; private final String dlqUrl;

    public UiQueryController(AssetRepository assets, ScanRepository scans, FindingRepository findings,
            UiAgentRepository agents, UiScanRequestService scanRequests, AwsPublicationOutboxRepository outbox,
            UiProperties properties, Environment environment, ObjectProvider<SqsClient> sqs,
            ObjectProvider<AwsIngestionProperties> aws, ObjectProvider<ProcessingResultReader> resultReaders,
            @Value("${VULNFLOW_SQS_DLQ_URL:}") String dlqUrl) {
        this.assets=assets; this.scans=scans; this.findings=findings; this.agents=agents;
        this.scanRequests=scanRequests; this.outbox=outbox; this.properties=properties;
        this.environment=environment; this.sqs=sqs; this.aws=aws; this.resultReaders=resultReaders;
        this.dlqUrl=dlqUrl;
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard() {
        Instant since=Instant.now().minusSeconds(30L*86400);
        List<Scan> recent=scans.findByReceivedAtAfterOrderByReceivedAtDesc(since,PageRequest.of(0,500)).getContent();
        List<UUID> assetIds=recent.stream().map(scan->scan.getAsset().getId()).distinct().toList();
        List<Scan> current=assetIds.isEmpty()?List.of():scans.findLatestByAssetIds(assetIds);
        Map<UUID,ProcessingResultSummary> summaries=summaries(current);
        Map<String,Long> severity=new LinkedHashMap<>(); long findingCount=0;
        for(FindingSeverity value:FindingSeverity.values()){
            long count=0;
            for(Scan scan:current){ProcessingResultSummary summary=summaries.get(scan.getId());count+=summary==null
                    ?findings.countByScanIdAndSeverity(scan.getId(),value)
                    :summary.severitySummary().getOrDefault(value.name(),0);}
            severity.put(value.name(),count); findingCount+=count;
        }
        UiAgent agent=agents.findAllByOrderByLastHeartbeatAtDesc().stream().findFirst().orElse(null);
        return new Dashboard(since,500,recent.size(),current.size(),findingCount,severity,
                agent==null?null:AgentView.from(agent,properties.agentOfflineAfter()));
    }

    @GetMapping("/assets")
    public Page<AssetView> assets(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){
        Page<Asset> result=assets.findAll(PageRequest.of(Math.max(page,0),bounded(size)));
        List<UUID> ids=result.getContent().stream().map(Asset::getId).toList();
        Map<UUID,Scan> latest=ids.isEmpty()?Map.of():scans.findLatestByAssetIds(ids).stream().collect(
                Collectors.toMap(scan->scan.getAsset().getId(),scan->scan,(left,right)->left));
        Map<UUID,ProcessingResultSummary> summaries=summaries(latest.values().stream().toList());
        return result.map(asset->AssetView.from(asset,latest.get(asset.getId()),summaries));
    }

    @GetMapping("/assets/{id}/scans")
    public Page<ScanResponse> assetScans(@PathVariable UUID id,@RequestParam(defaultValue="0")int page,
            @RequestParam(defaultValue="25")int size){requireAsset(id);return scans.findByAssetIdOrderByReceivedAtDesc(
                    id,PageRequest.of(Math.max(page,0),bounded(size))).map(ScanResponse::from);}

    @GetMapping("/results")
    public Page<ResultView> results(@RequestParam(required=false)UUID assetId,
            @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){
        Pageable pageable=PageRequest.of(Math.max(page,0),bounded(size));
        Page<Scan> result=assetId==null?scans.findAllByOrderByReceivedAtDesc(pageable)
                :scans.findByAssetIdOrderByReceivedAtDesc(assetId,pageable);
        Map<UUID,ProcessingResultSummary> summaries=summaries(result.getContent());
        return result.map(scan->ResultView.from(scan,summaries.get(scan.getId()),findings));
    }

    @GetMapping("/results/{id}") public ResultSummary resultSummary(@PathVariable UUID id){return summary(requireScan(id));}

    @GetMapping("/results/{id}/findings")
    public FindingsPage resultFindings(@PathVariable UUID id,@RequestParam(defaultValue="0")int page,
            @RequestParam(defaultValue="25")int size,@RequestParam(required=false)String query,
            @RequestParam(required=false)FindingSeverity severity,@RequestParam(required=false)String cursor){
        requireScan(id);
        String needle=query==null?"":query.trim().toLowerCase(Locale.ROOT);
        if(needle.length()>100)throw new IllegalArgumentException("query is too long");
        int limit=bounded(size);
        ProcessingResultReader reader=resultReaders.getIfAvailable();
        if(reader==null||findings.countByScanId(id)>0){
            Page<Finding> local=findings.searchByScanId(id,needle,severity,PageRequest.of(Math.max(page,0),limit));
            return new FindingsPage(local.map(FindingView::from).getContent(),null,local.getNumber(),
                    local.getTotalPages(),local.getTotalElements(),false,true);
        }
        return searchAwsFindings(reader,id,cursor,limit,needle,severity);
    }

    @GetMapping("/scan-requests/{id}/findings")
    public FindingsPage requestFindings(@PathVariable UUID id,@AuthenticationPrincipal UiPrincipal principal,
            @RequestParam(defaultValue="0")int page,@RequestParam(required=false)String cursor,
            @RequestParam(defaultValue="25")int size){
        UUID scanId=scanRequests.authorizeResultAccess(id,principal);int limit=bounded(size);
        Page<Finding> local=findings.findByScanId(scanId,PageRequest.of(Math.max(page,0),limit));
        if(local.hasContent()||resultReaders.getIfAvailable()==null)return new FindingsPage(
                local.map(FindingView::from).getContent(),null,local.getNumber(),local.getTotalPages(),local.getTotalElements(),false,true);
        var awsPage=resultReaders.getIfAvailable().findFindings(scanId,cursor,limit);
        return new FindingsPage(awsPage.findings().stream().map(FindingView::from).toList(),awsPage.nextCursor(),0,
                awsPage.nextCursor()==null?1:2,awsPage.findings().size(),false,false);
    }

    @GetMapping("/scan-requests/{id}/summary")
    public ResultSummary requestSummary(@PathVariable UUID id,@AuthenticationPrincipal UiPrincipal principal){
        return summary(requireScan(scanRequests.authorizeResultAccess(id,principal)));}

    @GetMapping("/operations")
    public Operations operations(){UiAgent agent=agents.findAllByOrderByLastHeartbeatAtDesc().stream().findFirst().orElse(null);
        Map<String,Long> publication=Map.of("pending",outbox.countByStatus(AwsPublicationStatus.PUBLISH_PENDING),
                "publishing",outbox.countByStatus(AwsPublicationStatus.PUBLISHING),
                "published",outbox.countByStatus(AwsPublicationStatus.PUBLISHED),
                "failed",outbox.countByStatus(AwsPublicationStatus.FAILED));
        return new Operations(List.of(environment.getActiveProfiles()),agent==null?null:AgentView.from(agent,properties.agentOfflineAfter()),
                publication,queueTelemetry(),properties.sqsTelemetryEnabled(),properties.scansEnabled());}

    private Map<UUID,ProcessingResultSummary> summaries(List<Scan> list){ProcessingResultReader reader=resultReaders.getIfAvailable();
        return reader==null||list.isEmpty()?Map.of():reader.findScans(list.stream().map(Scan::getId).toList());}
    private ResultSummary summary(Scan scan){ProcessingResultReader reader=resultReaders.getIfAvailable();
        if(reader!=null){ProcessingResultSummary stored=reader.findScan(scan.getId()).orElse(null);if(stored!=null)return ResultSummary.from(stored);}
        Map<String,Integer> severity=new LinkedHashMap<>();for(FindingSeverity value:FindingSeverity.values())
            severity.put(value.name(),(int)findings.countByScanIdAndSeverity(scan.getId(),value));
        return new ResultSummary(scan.getId(),null,scan.getStatus().name(),scan.getScanner().name(),scan.getScannerVersion(),
                scan.getContentHash(),scan.getReceivedAt(),scan.getCompletedAt(),(int)findings.countByScanId(scan.getId()),severity,scan.getFailureReason());}
    private FindingsPage searchAwsFindings(ProcessingResultReader reader,UUID scanId,String cursor,int limit,
            String needle,FindingSeverity severity){
        List<FindingView> matches=new ArrayList<>();
        String next=cursor;
        int pages=0;
        do{
            var batch=reader.findFindings(scanId,next,limit-matches.size());
            next=batch.nextCursor();pages++;
            batch.findings().stream().map(FindingView::from)
                    .filter(finding->matches(finding,needle,severity))
                    .limit((long) limit-matches.size()).forEach(matches::add);
        }while(next!=null&&matches.size()<limit&&pages<MAX_AWS_SEARCH_PAGES);
        boolean budgetExhausted=next!=null&&matches.size()<limit&&pages>=MAX_AWS_SEARCH_PAGES;
        return new FindingsPage(matches,next,0,next==null?1:2,matches.size(),budgetExhausted,false);
    }
    private static boolean matches(FindingView finding,String needle,FindingSeverity severity){
        return (severity==null||severity.name().equals(finding.severity()))
                &&(needle.isEmpty()||contains(finding.vulnerabilityId(),needle)||contains(finding.packageName(),needle)
                ||contains(finding.title(),needle));
    }
    private Asset requireAsset(UUID id){return assets.findById(id).orElseThrow(()->new ResourceNotFoundException("Asset",id));}
    private Scan requireScan(UUID id){return scans.findById(id).orElseThrow(()->new ResourceNotFoundException("Scan",id));}
    private int bounded(int size){return Math.max(1,Math.min(size,100));}
    private static boolean contains(String value,String needle){return value!=null&&value.toLowerCase(Locale.ROOT).contains(needle);}

    private QueueTelemetry queueTelemetry(){if(!properties.sqsTelemetryEnabled())return null;SqsClient client=sqs.getIfAvailable();
        AwsIngestionProperties config=aws.getIfAvailable();if(client==null||config==null||dlqUrl.isBlank())return new QueueTelemetry(null,null,"unavailable");
        try{return new QueueTelemetry(attributes(client,config.sqsQueueUrl()),attributes(client,dlqUrl),"healthy");}
        catch(RuntimeException exception){return new QueueTelemetry(null,null,"degraded");}}
    private QueueCounts attributes(SqsClient client,String url){var values=client.getQueueAttributes(request->request.queueUrl(url)
            .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
        return new QueueCounts(parse(values.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)),parse(values.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)));}
    private long parse(String value){try{return Long.parseLong(value);}catch(RuntimeException ignored){return 0;}}

    public record Dashboard(Instant since,int scanLimit,long scans,long assets,long findings,Map<String,Long> severity,AgentView agent){}
    public record AssetView(UUID id,String name,String type,String reference,Instant updatedAt,UUID lastScanId,String lastScanStatus,
                            Instant lastScanReceivedAt,Instant lastScanCompletedAt){static AssetView from(Asset asset,Scan latest,Map<UUID,ProcessingResultSummary> summaries){
        ProcessingResultSummary summary=latest==null?null:summaries.get(latest.getId());return new AssetView(asset.getId(),asset.getName(),asset.getType().name(),
                asset.getExternalReference(),asset.getUpdatedAt(),latest==null?null:latest.getId(),summary==null?(latest==null?null:latest.getStatus().name()):summary.status().name(),
                summary==null?(latest==null?null:latest.getReceivedAt()):summary.receivedAt(),summary==null?(latest==null?null:latest.getCompletedAt()):summary.completedAt());}}
    public record ResultView(UUID id,UUID assetId,String assetName,String reference,String status,String scanner,int findingCount,
                             Map<String,Integer> severity,Instant receivedAt,Instant completedAt){static ResultView from(Scan scan,ProcessingResultSummary summary,FindingRepository findings){
        Map<String,Integer> severity=new LinkedHashMap<>();for(FindingSeverity value:FindingSeverity.values())severity.put(value.name(),summary==null
                ?(int)findings.countByScanIdAndSeverity(scan.getId(),value):summary.severitySummary().getOrDefault(value.name(),0));
        return new ResultView(scan.getId(),scan.getAsset().getId(),scan.getAsset().getName(),scan.getAsset().getExternalReference(),
                summary==null?scan.getStatus().name():summary.status().name(),scan.getScanner().name(),summary==null?(int)findings.countByScanId(scan.getId()):summary.findingCount(),
                severity,summary==null?scan.getReceivedAt():summary.receivedAt(),summary==null?scan.getCompletedAt():summary.completedAt());}}
    public record FindingView(String id,String vulnerabilityId,String packageName,String installedVersion,String fixedVersion,String severity,String title,int riskScore,boolean knownExploited){
        static FindingView from(Finding finding){return new FindingView(finding.getId().toString(),finding.getVulnerabilityId(),finding.getPackageName(),finding.getInstalledVersion(),
                finding.getFixedVersion(),finding.getSeverity().name(),finding.getTitle(),finding.getRiskScore(),finding.isKnownExploited());}
        static FindingView from(ProcessingFindingResult finding){return new FindingView(finding.findingKey(),finding.vulnerabilityId(),finding.packageName(),finding.installedVersion(),
                finding.fixedVersion(),finding.severity().name(),finding.title(),finding.riskScore(),finding.knownExploited());}}
    public record ResultSummary(UUID scanId,UUID correlationId,String status,String scanner,String scannerVersion,String contentHash,Instant receivedAt,Instant completedAt,
                                int findingCount,Map<String,Integer> severitySummary,String safeError){static ResultSummary from(ProcessingResultSummary result){return new ResultSummary(result.scanId(),result.correlationId(),
        result.status().name(),result.scanner(),result.scannerVersion(),result.contentHash(),result.receivedAt(),result.completedAt(),result.findingCount(),result.severitySummary(),result.safeError());}}
    public record FindingsPage(List<FindingView> content,String nextCursor,int number,int totalPages,long totalElements,
                               boolean truncated,boolean totalExact){}
    public record AgentView(String id,String status,boolean online,Instant lastHeartbeatAt,int outboxPending,int deadLetters,long outboxBytes,long diskFreeBytes,String safeError){
        static AgentView from(UiAgent agent,java.time.Duration offline){return new AgentView(agent.getId(),agent.getStatus(),agent.getLastHeartbeatAt().isAfter(Instant.now().minus(offline)),
                agent.getLastHeartbeatAt(),agent.getOutboxPending(),agent.getOutboxDeadLetters(),agent.getOutboxBytes(),agent.getDiskFreeBytes(),agent.getLastError());}}
    public record QueueCounts(long visible,long inFlight){} public record QueueTelemetry(QueueCounts source,QueueCounts dlq,String status){}
    public record Operations(List<String> activeProfiles,AgentView agent,Map<String,Long> publicationOutbox,QueueTelemetry queues,boolean sqsTelemetryEnabled,boolean scansEnabled){}
}
