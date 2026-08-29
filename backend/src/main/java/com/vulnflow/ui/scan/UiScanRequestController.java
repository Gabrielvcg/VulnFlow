package com.vulnflow.ui.scan;

import com.vulnflow.ui.auth.UiPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/ui/v1/scan-requests")
public class UiScanRequestController {
    private final UiScanRequestService service;
    public UiScanRequestController(UiScanRequestService service){this.service=service;}
    @PostMapping public UiScanRequestService.ScanRequestResponse create(@AuthenticationPrincipal UiPrincipal principal,@Valid @RequestBody CreateRequest body){return service.create(body.targetId(),principal);}
    @GetMapping public Page<UiScanRequestService.ScanRequestResponse> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){return service.list(PageRequest.of(page,Math.min(100,size)));}
    @GetMapping("/{id}") public UiScanRequestService.ScanRequestResponse get(@PathVariable UUID id,@AuthenticationPrincipal UiPrincipal principal){return service.get(id,principal);}
    public record CreateRequest(@NotNull UUID targetId){}
}
