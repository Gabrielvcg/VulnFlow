package com.vulnflow.scan;

import java.time.Instant;
import java.util.UUID;

public record ScanResponse(
        UUID id,
        UUID assetId,
        ScannerType scanner,
        String scannerVersion,
        ScanStatus status,
        Instant startedAt,
        Instant completedAt,
        Instant receivedAt,
        String sourceFileName,
        String contentHash,
        String failureReason) {

    public static ScanResponse from(Scan scan) {
        return new ScanResponse(
                scan.getId(),
                scan.getAsset().getId(),
                scan.getScanner(),
                scan.getScannerVersion(),
                scan.getStatus(),
                scan.getStartedAt(),
                scan.getCompletedAt(),
                scan.getReceivedAt(),
                scan.getSourceFileName(),
                scan.getContentHash(),
                scan.getFailureReason());
    }
}
