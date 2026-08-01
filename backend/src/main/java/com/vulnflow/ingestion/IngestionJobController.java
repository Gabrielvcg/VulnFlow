package com.vulnflow.ingestion;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!aws")
@RequestMapping("/api/v1/ingestion-jobs")
public class IngestionJobController {

    private final IngestionJobQueryService queryService;
    private final IngestionJobRedriveService redriveService;

    public IngestionJobController(
            IngestionJobQueryService queryService,
            IngestionJobRedriveService redriveService) {
        this.queryService = queryService;
        this.redriveService = redriveService;
    }

    @GetMapping
    public Page<IngestionJobResponse> findAll(
            @RequestParam(required = false) IngestionJobStatus status,
            @RequestParam(required = false) UUID scanId,
            @PageableDefault(
                    size = 20,
                    sort = {"createdAt", "id"},
                    direction = Sort.Direction.DESC)
            Pageable pageable) {
        return queryService.findAll(status, scanId, pageable);
    }

    @GetMapping("/{jobId}")
    public IngestionJobResponse findById(@PathVariable UUID jobId) {
        return queryService.findById(jobId);
    }

    @PostMapping("/{jobId}/redrive")
    public ResponseEntity<IngestionJobResponse> redrive(@PathVariable UUID jobId) {
        return ResponseEntity.accepted().body(redriveService.redrive(jobId));
    }
}
