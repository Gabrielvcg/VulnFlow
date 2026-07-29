package com.vulnflow.finding;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/findings")
public class FindingController {

    private final FindingService findingService;

    public FindingController(FindingService findingService) {
        this.findingService = findingService;
    }

    @GetMapping
    public Page<FindingDtos.Response> findAll(
            @RequestParam(required = false) FindingSeverity severity,
            @RequestParam(required = false) FindingStatus status,
            @RequestParam(required = false) UUID assetId,
            @RequestParam(required = false) Boolean knownExploited,
            @PageableDefault(size = 20) Pageable pageable) {
        return findingService.findAll(severity, status, assetId, knownExploited, pageable);
    }

    @GetMapping("/{id}")
    public FindingDtos.Response findById(@PathVariable UUID id) {
        return findingService.findById(id);
    }

    @PatchMapping("/{id}/status")
    public FindingDtos.Response updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody FindingDtos.StatusUpdateRequest request) {
        return findingService.updateStatus(id, request);
    }
}

