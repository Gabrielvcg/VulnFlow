package com.vulnflow.ingestion;

import com.vulnflow.finding.Finding;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.FindingRiskCalculator;
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
public class IngestionPersistenceService {

    private final FindingRepository findingRepository;
    private final FindingRiskCalculator riskCalculator;
    private final ScanRepository scanRepository;
    private final IngestionJobRepository jobRepository;

    public IngestionPersistenceService(
            FindingRepository findingRepository,
            FindingRiskCalculator riskCalculator,
            ScanRepository scanRepository,
            IngestionJobRepository jobRepository) {
        this.findingRepository = findingRepository;
        this.riskCalculator = riskCalculator;
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID jobId, UUID expectedClaimToken, ParsedVulnerabilityReport report) {
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

        List<Finding> findings = report.vulnerabilities().stream()
                .map(vulnerability -> toFinding(scan, vulnerability))
                .toList();
        findingRepository.deleteByScanId(scan.getId());
        findingRepository.saveAll(findings);
        scan.markCompleted(report.scannerVersion());
        job.markCompleted(Instant.now());
    }

    private Finding toFinding(Scan scan, ParsedVulnerability vulnerability) {
        boolean knownExploited = false;
        return new Finding(
                scan,
                scan.getAsset(),
                vulnerability.vulnerabilityId(),
                vulnerability.packageName(),
                vulnerability.installedVersion(),
                vulnerability.fixedVersion(),
                vulnerability.severity(),
                vulnerability.title(),
                vulnerability.description(),
                knownExploited,
                riskCalculator.calculate(vulnerability.severity(), knownExploited));
    }
}
