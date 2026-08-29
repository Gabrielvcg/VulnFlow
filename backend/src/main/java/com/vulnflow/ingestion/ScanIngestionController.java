package com.vulnflow.ingestion;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.vulnflow.ui.scan.UiScanRequestService;

@RestController
@RequestMapping("/api/v1/scans")
public class ScanIngestionController {

    private final ScanIngestionService scanIngestionService;
    private final UiScanRequestService scanRequestService;

    public ScanIngestionController(ScanIngestionService scanIngestionService, UiScanRequestService scanRequestService) {
        this.scanIngestionService = scanIngestionService;
        this.scanRequestService = scanRequestService;
    }

    @PostMapping(value = "/trivy", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ScanIngestionResponse> ingestTrivy(
            @RequestParam UUID assetId,
            @RequestParam(required = false) UUID scanRequestId,
            @RequestParam(required = false) UUID claimToken,
            @RequestPart("file") MultipartFile file) {
        if ((scanRequestId == null) != (claimToken == null)) {
            throw new IllegalArgumentException("scanRequestId and claimToken must be provided together");
        }
        if (scanRequestId != null) {
            scanRequestService.verifyUpload(scanRequestId, claimToken);
        }
        ScanIngestionResponse response = scanIngestionService.ingestTrivy(assetId, file);
        if (scanRequestId != null) {
            scanRequestService.associateUpload(scanRequestId, claimToken, response.scanId(), response.eventId());
        }
        if (response.outcome() == ScanIngestionOutcome.DUPLICATE
                || response.outcome() == ScanIngestionOutcome.DEAD_LETTER) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.accepted().body(response);
    }
}
