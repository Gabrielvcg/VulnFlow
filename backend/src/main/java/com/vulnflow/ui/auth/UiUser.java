package com.vulnflow.ui.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ui_users")
public class UiUser {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 100) private String username;
    @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private UiRole role;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "password_change_required", nullable = false) private boolean passwordChangeRequired = true;
    @Column(name = "failed_login_attempts", nullable = false) private int failedLoginAttempts;
    @Column(name = "locked_until") private Instant lockedUntil;
    @Column(name = "last_login_at") private Instant lastLoginAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected UiUser() {}

    public UiUser(String username, String passwordHash, UiRole role, boolean passwordChangeRequired) {
        this.id = UUID.randomUUID();
        this.username = username.trim().toLowerCase(java.util.Locale.ROOT);
        this.passwordHash = passwordHash;
        this.role = role;
        this.passwordChangeRequired = passwordChangeRequired;
    }

    @PrePersist void create() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void update() { updatedAt = Instant.now(); }

    public boolean isLocked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }
    public void recordFailure(Instant now) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= 5) { lockedUntil = now.plusSeconds(900); failedLoginAttempts = 0; }
    }
    public void recordLogin(Instant now) { failedLoginAttempts = 0; lockedUntil = null; lastLoginAt = now; }
    public void changePassword(String hash) { passwordHash = hash; passwordChangeRequired = false; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void requirePasswordChange(String hash) { passwordHash = hash; passwordChangeRequired = true; lockedUntil = null; failedLoginAttempts = 0; }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public UiRole getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public boolean isPasswordChangeRequired() { return passwordChangeRequired; }
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
}
