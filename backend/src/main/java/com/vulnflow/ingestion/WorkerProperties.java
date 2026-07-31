package com.vulnflow.ingestion;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "vulnflow.worker")
public record WorkerProperties(
        boolean enabled,
        Duration pollInterval,
        @Min(1) int batchSize,
        @Min(1) int maxAttempts,
        Duration staleTimeout,
        @NotEmpty List<Duration> backoff) {

    public Duration backoffForAttempt(int attempt) {
        int index = Math.min(Math.max(attempt - 1, 0), backoff.size() - 1);
        return backoff.get(index);
    }
}
