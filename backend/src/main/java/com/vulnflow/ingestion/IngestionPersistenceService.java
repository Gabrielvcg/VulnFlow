package com.vulnflow.ingestion;

import com.vulnflow.finding.Finding;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.FindingRiskCalculator;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import java.util.List;
import org.springframework.stereotype.Service;
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

    @Transactional
    public void complete(Scan scan, ParsedVulnerabilityReport report) {
        List<Finding> findings = report.vulnerabilities().stream()
                .map(vulnerability -> toFinding(scan, vulnerability))
                .toList();
        findingRepository.saveAll(findings);
        scan.markCompleted(report.scannerVersion());
        scanRepository.save(scan);
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

