package com.vulnflow.scan;

import com.vulnflow.asset.Asset;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scans")
public class Scan {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScannerType scanner;

    @Column(name = "scanner_version", length = 100)
    private String scannerVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScanStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "source_file_name", nullable = false, length = 500)
    private String sourceFileName;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    protected Scan() {
    }

    public Scan(Asset asset, ScannerType scanner, String sourceFileName, String contentHash) {
        this.id = UUID.randomUUID();
        this.asset = asset;
        this.scanner = scanner;
        this.sourceFileName = sourceFileName;
        this.contentHash = contentHash;
        this.status = ScanStatus.RECEIVED;
        this.receivedAt = Instant.now();
    }

    public void markProcessing() {
        status = ScanStatus.PROCESSING;
        startedAt = Instant.now();
        completedAt = null;
        failureReason = null;
    }

    public void markReceived() {
        status = ScanStatus.RECEIVED;
        scannerVersion = null;
        startedAt = null;
        completedAt = null;
        failureReason = null;
    }

    public void markCompleted(String scannerVersion) {
        this.scannerVersion = scannerVersion;
        status = ScanStatus.COMPLETED;
        completedAt = Instant.now();
        failureReason = null;
    }

    public void markFailed(String failureReason) {
        status = ScanStatus.FAILED;
        completedAt = Instant.now();
        this.failureReason = failureReason;
    }

    public UUID getId() {
        return id;
    }

    public Asset getAsset() {
        return asset;
    }

    public ScannerType getScanner() {
        return scanner;
    }

    public String getScannerVersion() {
        return scannerVersion;
    }

    public ScanStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
