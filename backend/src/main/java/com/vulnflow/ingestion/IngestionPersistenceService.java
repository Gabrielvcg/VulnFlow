package com.vulnflow.ingestion;

import com.vulnflow.finding.Finding;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.FindingSeverity;
import com.vulnflow.processing.NormalizedFinding;
import com.vulnflow.processing.ProcessedVulnerabilityReport;
import com.vulnflow.processing.port.ProcessingResultStore;
import com.vulnflow.processing.port.ProcessingStoreOutcome;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScanStatus;
import com.vulnflow.shared.exception.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionPersistenceService implements ProcessingResultStore<LocalCompletionContext> {

    private final FindingRepository findingRepository;
    private final ScanRepository scanRepository;
    private final IngestionJobRepository jobRepository;

    public IngestionPersistenceService(
            FindingRepository findingRepository,
            ScanRepository scanRepository,
            IngestionJobRepository jobRepository) {
        this.findingRepository = findingRepository;
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public ProcessingStoreOutcome store(LocalCompletionContext context, ProcessedVulnerabilityReport report) {
        UUID jobId = context.jobId();
        UUID expectedClaimToken = context.expectedClaimToken();
        IngestionJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("IngestionJob", jobId));
        if (job.getStatus() != IngestionJobStatus.PROCESSING
                || !Objects.equals(job.getClaimToken(), expectedClaimToken)) {
            throw new StaleJobClaimException("The ingestion job claim is no longer current");
        }
        Scan scan = scanRepository.findByIdForUpdate(job.getScan().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Scan", job.getScan().getId()));
        if (scan.getStatus() != ScanStatus.PROCESSING) {
            throw new IllegalStateException("Only a processing scan can be completed");
        }
        if (!scan.getId().equals(report.scanId()) || !scan.getAsset().getId().equals(report.assetId())) {
            throw new IllegalArgumentException("The processing result does not belong to the claimed scan");
        }

        List<Finding> findings = report.findings().stream()
                .map(vulnerability -> toFinding(scan, vulnerability))
                .toList();
        findingRepository.deleteByScanId(scan.getId());
        findingRepository.saveAll(findings);
        scan.markCompleted(report.scannerVersion());
        job.markCompleted(Instant.now());
        return ProcessingStoreOutcome.STORED;
    }

    private Finding toFinding(Scan scan, NormalizedFinding vulnerability) {
        return new Finding(
                scan,
                scan.getAsset(),
                vulnerability.vulnerabilityId(),
                vulnerability.packageName(),
                vulnerability.installedVersion(),
                vulnerability.fixedVersion(),
                FindingSeverity.valueOf(vulnerability.severity().name()),
                vulnerability.title(),
                vulnerability.description(),
                vulnerability.knownExploited(),
                vulnerability.riskScore());
    }
}
