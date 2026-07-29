package com.vulnflow.dashboard;

import com.vulnflow.asset.AssetRepository;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.FindingSeverity;
import com.vulnflow.finding.FindingStatus;
import com.vulnflow.scan.ScanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    private final AssetRepository assetRepository;
    private final ScanRepository scanRepository;
    private final FindingRepository findingRepository;

    public DashboardService(
            AssetRepository assetRepository,
            ScanRepository scanRepository,
            FindingRepository findingRepository) {
        this.assetRepository = assetRepository;
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSummary summary() {
        return new DashboardSummary(
                assetRepository.count(),
                scanRepository.count(),
                findingRepository.count(),
                findingRepository.countByStatus(FindingStatus.OPEN),
                findingRepository.countBySeverity(FindingSeverity.CRITICAL),
                findingRepository.countBySeverity(FindingSeverity.HIGH),
                findingRepository.countByKnownExploitedTrue());
    }
}

