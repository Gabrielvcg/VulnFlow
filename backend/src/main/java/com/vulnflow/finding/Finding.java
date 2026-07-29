package com.vulnflow.finding;

import com.vulnflow.asset.Asset;
import com.vulnflow.scan.Scan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "findings")
public class Finding {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false)
    private Scan scan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Column(name = "vulnerability_id", nullable = false)
    private String vulnerabilityId;

    @Column(name = "package_name", nullable = false, length = 500)
    private String packageName;

    @Column(name = "installed_version")
    private String installedVersion;

    @Column(name = "fixed_version")
    private String fixedVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FindingSeverity severity;

    @Column(length = 1000)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FindingStatus status;

    @Column(name = "known_exploited", nullable = false)
    private boolean knownExploited;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Finding() {
    }

    public Finding(
            Scan scan,
            Asset asset,
            String vulnerabilityId,
            String packageName,
            String installedVersion,
            String fixedVersion,
            FindingSeverity severity,
            String title,
            String description,
            boolean knownExploited,
            int riskScore) {
        this.id = UUID.randomUUID();
        this.scan = scan;
        this.asset = asset;
        this.vulnerabilityId = vulnerabilityId;
        this.packageName = packageName;
        this.installedVersion = installedVersion;
        this.fixedVersion = fixedVersion;
        this.severity = severity;
        this.title = title;
        this.description = description;
        this.status = FindingStatus.OPEN;
        this.knownExploited = knownExploited;
        this.riskScore = riskScore;
        this.detectedAt = Instant.now();
        this.updatedAt = this.detectedAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void updateStatus(FindingStatus status) {
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public Scan getScan() {
        return scan;
    }

    public Asset getAsset() {
        return asset;
    }

    public String getVulnerabilityId() {
        return vulnerabilityId;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getInstalledVersion() {
        return installedVersion;
    }

    public String getFixedVersion() {
        return fixedVersion;
    }

    public FindingSeverity getSeverity() {
        return severity;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public FindingStatus getStatus() {
        return status;
    }

    public boolean isKnownExploited() {
        return knownExploited;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

