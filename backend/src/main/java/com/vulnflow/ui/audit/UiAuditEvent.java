package com.vulnflow.ui.audit;

import com.vulnflow.ui.auth.UiUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ui_audit_events")
public class UiAuditEvent {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "actor_user_id") private UiUser actor;
    @Column(name = "actor_username", length = 100) private String actorUsername;
    @Column(nullable = false, length = 64) private String action;
    @Column(name = "subject_type", length = 64) private String subjectType;
    @Column(name = "subject_id", length = 100) private String subjectId;
    @Column(nullable = false, length = 16) private String outcome;
    @Column(name = "source_address_hash", length = 64) private String sourceAddressHash;
    @Column(length = 500) private String details;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected UiAuditEvent() {}
    public UiAuditEvent(UiUser actor, String actorUsername, String action, String subjectType,
                        String subjectId, String outcome, String sourceAddressHash, String details) {
        id = UUID.randomUUID(); this.actor = actor; this.actorUsername = actorUsername; this.action = action;
        this.subjectType = subjectType; this.subjectId = subjectId; this.outcome = outcome;
        this.sourceAddressHash = sourceAddressHash; this.details = details; createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getActorUsername() { return actorUsername; }
    public String getAction() { return action; }
    public String getSubjectType() { return subjectType; }
    public String getSubjectId() { return subjectId; }
    public String getOutcome() { return outcome; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
