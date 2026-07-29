package com.vulnflow.ingestion;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/scans")
public class ScanIngestionController {

    private final ScanIngestionService scanIngestionService;

    public ScanIngestionController(ScanIngestionService scanIngestionService) {
        this.scanIngestionService = scanIngestionService;
    }

    @PostMapping(value = "/trivy", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ScanIngestionResponse ingestTrivy(
            @RequestParam UUID assetId,
            @RequestPart("file") MultipartFile file) {
        return scanIngestionService.ingestTrivy(assetId, file);
    }
}

