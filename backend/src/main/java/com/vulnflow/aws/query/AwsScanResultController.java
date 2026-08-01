package com.vulnflow.aws.query;

import com.vulnflow.processing.port.ProcessingFindingPage;
import com.vulnflow.processing.port.ProcessingResultSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@Profile("aws")
@RequestMapping("/api/v1/scans")
public class AwsScanResultController {
    private final AwsResultQueryService queryService;

    public AwsScanResultController(AwsResultQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{id}")
    public ProcessingResultSummary findById(@PathVariable UUID id) {
        return queryService.findScan(id);
    }

    @GetMapping("/{id}/findings")
    public ProcessingFindingPage findFindings(
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return queryService.findFindings(id, cursor, size);
    }
}
