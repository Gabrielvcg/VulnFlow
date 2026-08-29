package com.vulnflow.aws.ingestion;

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
@Table(name = "aws_publication_outbox")
public class AwsPublicationOutbox {
    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false, unique = true)
    private Scan scan;

    @Column(name = "payload_key", nullable = false, unique = true, length = 1024)
    private String payloadKey;

    @Column(name = "event_json", nullable = false, length = 4000)
    private String eventJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AwsPublicationStatus status;

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

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AwsPublicationOutbox() {
    }

    public AwsPublicationOutbox(
            UUID eventId,
            Scan scan,
            String payloadKey,
            String eventJson,
            int maxAttempts,
            Instant now) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.eventId = eventId;
        this.scan = scan;
        this.payloadKey = payloadKey;
        this.eventJson = eventJson;
        this.status = AwsPublicationStatus.PUBLISH_PENDING;
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
        if (status != AwsPublicationStatus.PUBLISH_PENDING || attemptCount >= maxAttempts) {
            throw new IllegalStateException("The outbox event is not claimable");
        }
        status = AwsPublicationStatus.PUBLISHING;
        attemptCount++;
        lockedAt = now;
        claimToken = UUID.randomUUID();
        lastError = null;
        return claimToken;
    }

    public void markPublished(Instant now) {
        requirePublishing();
        status = AwsPublicationStatus.PUBLISHED;
        publishedAt = now;
        lockedAt = null;
        claimToken = null;
        lastError = null;
    }

    public void markPublicationFailure(Instant now, Duration backoff, String safeError, boolean permanent) {
        requirePublishing();
        boolean exhausted = attemptCount >= maxAttempts;
        status = permanent || exhausted ? AwsPublicationStatus.FAILED : AwsPublicationStatus.PUBLISH_PENDING;
        availableAt = permanent || exhausted ? now : now.plus(backoff);
        lockedAt = null;
        claimToken = null;
        lastError = limit(safeError);
    }

    public void recover(Instant now, Duration backoff) {
        markPublicationFailure(now, backoff, "Publication claim expired", false);
    }

    public void retryFailed(Instant now) {
        if (status != AwsPublicationStatus.FAILED) {
            throw new IllegalStateException("Only failed publications can be retried");
        }
        status = AwsPublicationStatus.PUBLISH_PENDING;
        attemptCount = 0;
        availableAt = now;
        lastError = null;
        publishedAt = null;
        lockedAt = null;
        claimToken = null;
    }

    private void requirePublishing() {
        if (status != AwsPublicationStatus.PUBLISHING) {
            throw new IllegalStateException("The outbox event is not being published");
        }
    }

    private String limit(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getEventId() { return eventId; }
    public Scan getScan() { return scan; }
    public String getPayloadKey() { return payloadKey; }
    public String getEventJson() { return eventJson; }
    public AwsPublicationStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getLockedAt() { return lockedAt; }
    public UUID getClaimToken() { return claimToken; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
