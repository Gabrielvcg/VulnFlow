package com.vulnflow.ingestion;

import com.vulnflow.finding.Finding;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.FindingRiskCalculator;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScanStatus;
import com.vulnflow.shared.exception.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionPersistenceService {

    private final FindingRepository findingRepository;
    private final FindingRiskCalculator riskCalculator;
    private final ScanRepository scanRepository;

    public IngestionPersistenceService(
            FindingRepository findingRepository,
            FindingRiskCalculator riskCalculator,
            ScanRepository scanRepository) {
        this.findingRepository = findingRepository;
        this.riskCalculator = riskCalculator;
        this.scanRepository = scanRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID scanId, ParsedVulnerabilityReport report) {
        Scan scan = scanRepository.findByIdForUpdate(scanId)
                .orElseThrow(() -> new ResourceNotFoundException("Scan", scanId));
        if (scan.getStatus() != ScanStatus.PROCESSING) {
            throw new IllegalStateException("Only a processing scan can be completed");
        }
        List<Finding> findings = report.vulnerabilities().stream()
                .map(vulnerability -> toFinding(scan, vulnerability))
                .toList();
        findingRepository.saveAll(findings);
        scan.markCompleted(report.scannerVersion());
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
