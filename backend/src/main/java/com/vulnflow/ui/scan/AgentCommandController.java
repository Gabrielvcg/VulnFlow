package com.vulnflow.ui.scan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/v1/agents/{agentId}")
public class AgentCommandController {
    private final UiScanRequestService service;
    public AgentCommandController(UiScanRequestService service){this.service=service;}
    @PostMapping("/heartbeat") @ResponseStatus(HttpStatus.NO_CONTENT) public void heartbeat(@PathVariable String agentId,@RequestBody UiScanRequestService.Heartbeat body){service.heartbeat(agentId,body);}
    @PostMapping("/scan-requests/claim") public UiScanRequestService.AgentClaim claim(@PathVariable String agentId,@RequestBody UiScanRequestService.Heartbeat body){return service.claim(agentId,body);}
    @PostMapping("/scan-requests/{id}/start") @ResponseStatus(HttpStatus.NO_CONTENT) public void start(@PathVariable String agentId,@PathVariable UUID id,@Valid @RequestBody TokenBody body){service.start(agentId,id,body.claimToken());}
    @PostMapping("/scan-requests/{id}/fail") @ResponseStatus(HttpStatus.NO_CONTENT) public void fail(@PathVariable String agentId,@PathVariable UUID id,@Valid @RequestBody FailureBody body){service.fail(agentId,id,body.claimToken(),body.safeError());}
    public record TokenBody(@NotNull UUID claimToken){}
    public record FailureBody(@NotNull UUID claimToken,@Size(max=500)String safeError){}
}
