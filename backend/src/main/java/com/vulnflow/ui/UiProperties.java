package com.vulnflow.ui;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vulnflow.ui")
public record UiProperties(
        boolean enabled,
        boolean scansEnabled,
        boolean sqsTelemetryEnabled,
        String bootstrapUsername,
        String bootstrapPasswordHash,
        Duration agentOfflineAfter,
        Duration claimLease,
        Duration requestExpiry,
        Duration targetCooldown,
        int queueCapacity,
        int maxHourlyPerUser,
        int maxDailyPerUser,
        int maxRecoveryAttempts,
        long agentMinFreeBytes) {
}
