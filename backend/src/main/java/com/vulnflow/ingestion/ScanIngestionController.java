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
        ScanIngestionResponse response;
        if (scanRequestId == null) {
            response = scanIngestionService.ingestTrivy(assetId, file);
        } else {
            PreparedScanUpload upload = scanIngestionService.prepareTrivy(assetId, file);
            var reservation = scanRequestService.reserveUpload(scanRequestId, claimToken, assetId, upload.contentHash());
            try {
                response = scanIngestionService.ingestCommandUpload(upload, reservation.acceptedScanId());
                if (reservation.acceptedScanId() == null) {
                    scanRequestService.associateUpload(scanRequestId, claimToken, reservation.attemptId(),
                            response.scanId(), response.eventId());
                }
            } catch (RuntimeException exception) {
                if (reservation.attemptId() != null) {
                    try {
                        scanRequestService.releaseUpload(scanRequestId, claimToken, reservation.attemptId());
                    } catch (RuntimeException releaseFailure) {
                        exception.addSuppressed(releaseFailure);
                    }
                }
                throw exception;
            }
        }        if (response.outcome() == ScanIngestionOutcome.DUPLICATE
                || response.outcome() == ScanIngestionOutcome.DEAD_LETTER) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.accepted().body(response);
    }
}
