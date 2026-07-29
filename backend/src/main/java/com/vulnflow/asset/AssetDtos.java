package com.vulnflow.asset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AssetDtos {

    private AssetDtos() {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 255) String name,
            @NotNull AssetType type,
            @Size(max = 500) String externalReference) {
    }

    public record Response(
            UUID id,
            String name,
            AssetType type,
            String externalReference,
            Instant createdAt,
            Instant updatedAt) {

        public static Response from(Asset asset) {
            return new Response(
                    asset.getId(),
                    asset.getName(),
                    asset.getType(),
                    asset.getExternalReference(),
                    asset.getCreatedAt(),
                    asset.getUpdatedAt());
        }
    }
}

