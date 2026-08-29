package com.vulnflow.ui.target;

import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetType;
import com.vulnflow.ui.auth.UiUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "ui_targets")
public class UiTarget {
    @Id private UUID id;
    @Column(nullable = false, length = 255) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AssetType type;
    @Column(name = "external_reference", nullable = false, length = 500) private String externalReference;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "asset_id") private Asset asset;
    @Column(nullable = false) private boolean enabled = true;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "created_by") private UiUser createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected UiTarget() {}
    public UiTarget(String name, String reference, Asset asset, UiUser createdBy) {
        id = UUID.randomUUID(); this.name = name; type = AssetType.CONTAINER_IMAGE;
        externalReference = reference; this.asset = asset; this.createdBy = createdBy;
    }
    @PrePersist void create() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void update() { updatedAt = Instant.now(); }
    public void update(String name, String reference, boolean enabled) { this.name = name; externalReference = reference; this.enabled = enabled; }
    public UUID getId() { return id; } public String getName() { return name; }
    public AssetType getType() { return type; } public String getExternalReference() { return externalReference; }
    public Asset getAsset() { return asset; } public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
}
