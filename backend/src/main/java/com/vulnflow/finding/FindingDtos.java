package com.vulnflow.finding;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class FindingDtos {

    private FindingDtos() {
    }

    public record StatusUpdateRequest(@NotNull FindingStatus status) {
    }

    public record SummaryResponse(
            UUID id,
            UUID scanId,
            UUID assetId,
            String vulnerabilityId,
            String packageName,
            String installedVersion,
            String fixedVersion,
            FindingSeverity severity,
            String title,
            FindingStatus status,
            boolean knownExploited,
            int riskScore,
            Instant detectedAt,
            Instant updatedAt) {

        public static SummaryResponse from(Finding finding) {
            return new SummaryResponse(
                    finding.getId(),
                    finding.getScan().getId(),
                    finding.getAsset().getId(),
                    finding.getVulnerabilityId(),
                    finding.getPackageName(),
                    finding.getInstalledVersion(),
                    finding.getFixedVersion(),
                    finding.getSeverity(),
                    finding.getTitle(),
                    finding.getStatus(),
                    finding.isKnownExploited(),
                    finding.getRiskScore(),
                    finding.getDetectedAt(),
                    finding.getUpdatedAt());
        }
    }

    public record Response(
            UUID id,
            UUID scanId,
            UUID assetId,
            String vulnerabilityId,
            String packageName,
            String installedVersion,
            String fixedVersion,
            FindingSeverity severity,
            String title,
            String description,
            FindingStatus status,
            boolean knownExploited,
            int riskScore,
            Instant detectedAt,
            Instant updatedAt) {

        public static Response from(Finding finding) {
            return new Response(
                    finding.getId(),
                    finding.getScan().getId(),
                    finding.getAsset().getId(),
                    finding.getVulnerabilityId(),
                    finding.getPackageName(),
                    finding.getInstalledVersion(),
                    finding.getFixedVersion(),
                    finding.getSeverity(),
                    finding.getTitle(),
                    finding.getDescription(),
                    finding.getStatus(),
                    finding.isKnownExploited(),
                    finding.getRiskScore(),
                    finding.getDetectedAt(),
                    finding.getUpdatedAt());
        }
    }
}
