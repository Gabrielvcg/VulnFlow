package com.vulnflow.scan;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scans")
public class ScanController {

    private final ScanQueryService scanQueryService;

    public ScanController(ScanQueryService scanQueryService) {
        this.scanQueryService = scanQueryService;
    }

    @GetMapping
    public Page<ScanResponse> findAll(
            @PageableDefault(
                    size = 20,
                    sort = {"receivedAt", "id"},
                    direction = Sort.Direction.DESC)
            Pageable pageable) {
        return scanQueryService.findAll(pageable);
    }

    @GetMapping("/{id}")
    public ScanResponse findById(@PathVariable UUID id) {
        return scanQueryService.findById(id);
    }
}
