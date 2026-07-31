package com.vulnflow.ingestion;

import com.vulnflow.scan.Scan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ingestion_jobs")
public class IngestionJob {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false, unique = true)
    private Scan scan;

    @Column(name = "payload_key", nullable = false, unique = true, length = 500)
    private String payloadKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IngestionJobStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IngestionJob() {
    }

    public IngestionJob(Scan scan, String payloadKey, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.scan = scan;
        this.payloadKey = payloadKey;
        this.status = IngestionJobStatus.PENDING;
        this.maxAttempts = maxAttempts;
        this.availableAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID claim(Instant now) {
        if (status != IngestionJobStatus.PENDING && status != IngestionJobStatus.RETRY_WAIT) {
            throw new IllegalStateException("Only an available job can be claimed");
        }
        if (attemptCount >= maxAttempts) {
            throw new IllegalStateException("The job has no attempts remaining");
        }
        status = IngestionJobStatus.PROCESSING;
        attemptCount++;
        lockedAt = now;
        claimToken = UUID.randomUUID();
        completedAt = null;
        lastError = null;
        return claimToken;
    }

    public void markCompleted(Instant now) {
        requireProcessing();
        status = IngestionJobStatus.COMPLETED;
        completedAt = now;
        lockedAt = null;
        claimToken = null;
        lastError = null;
    }

    public void scheduleRetry(Instant now, Duration backoff, String safeError) {
        requireProcessing();
        status = IngestionJobStatus.RETRY_WAIT;
        availableAt = now.plus(backoff);
        lockedAt = null;
        claimToken = null;
        completedAt = null;
        lastError = limitError(safeError);
    }

    public void markDeadLetter(Instant now, String safeError) {
        requireProcessing();
        status = IngestionJobStatus.DEAD_LETTER;
        completedAt = now;
        lockedAt = null;
        claimToken = null;
        lastError = limitError(safeError);
    }

    public void recoverToRetry(Instant now, Duration backoff, String safeError) {
        scheduleRetry(now, backoff, safeError);
    }

    public void redrive(Instant now) {
        if (status != IngestionJobStatus.DEAD_LETTER) {
            throw new IllegalStateException("Only a dead-letter job can be redriven");
        }
        status = IngestionJobStatus.PENDING;
        attemptCount = 0;
        availableAt = now;
        lockedAt = null;
        claimToken = null;
        completedAt = null;
        lastError = null;
    }

    private void requireProcessing() {
        if (status != IngestionJobStatus.PROCESSING) {
            throw new IllegalStateException("The job is not processing");
        }
    }

    private String limitError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getId() { return id; }
    public Scan getScan() { return scan; }
    public String getPayloadKey() { return payloadKey; }
    public IngestionJobStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getLockedAt() { return lockedAt; }
    public UUID getClaimToken() { return claimToken; }
    public Instant getCompletedAt() { return completedAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
