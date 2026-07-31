package com.vulnflow.ingestion;

import com.vulnflow.asset.Asset;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScanStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScanRegistrationService {

    private final ScanRepository scanRepository;
    private final FindingRepository findingRepository;

    public ScanRegistrationService(
            ScanRepository scanRepository,
            FindingRepository findingRepository) {
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScanRegistration registerProcessing(
            Asset asset,
            String sourceFileName,
            String contentHash) {
        UUID candidateId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        int inserted = scanRepository.insertProcessingIfAbsent(
                candidateId,
                asset.getId(),
                sourceFileName,
                contentHash,
                startedAt);

        Scan scan = scanRepository.findByAssetIdAndContentHashForUpdate(asset.getId(), contentHash)
                .orElseThrow(() -> new IllegalStateException("The registered scan could not be loaded"));

        if (inserted == 1) {
            return registration(scan, ScanIngestionOutcome.IMPORTED);
        }

        return switch (scan.getStatus()) {
            case COMPLETED -> registration(scan, ScanIngestionOutcome.DUPLICATE);
            case PROCESSING -> registration(scan, ScanIngestionOutcome.ALREADY_PROCESSING);
            case FAILED, RECEIVED -> retryFailedScan(scan, sourceFileName);
        };
    }

    private ScanRegistration retryFailedScan(Scan scan, String sourceFileName) {
        findingRepository.deleteByScanId(scan.getId());
        scan.retryProcessing(sourceFileName);
        return registration(scan, ScanIngestionOutcome.RETRIED);
    }

    private ScanRegistration registration(Scan scan, ScanIngestionOutcome outcome) {
        return new ScanRegistration(
                scan.getId(),
                scan.getAsset().getId(),
                scan.getStatus(),
                outcome);
    }
}
