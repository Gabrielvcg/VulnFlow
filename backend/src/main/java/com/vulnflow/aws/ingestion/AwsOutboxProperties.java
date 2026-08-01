package com.vulnflow.aws.ingestion;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

@Validated
@Profile("aws")
@ConfigurationProperties(prefix = "vulnflow.aws.outbox")
public record AwsOutboxProperties(
        boolean enabled,
        @Min(1) @Max(100) int batchSize,
        @Min(1) int maxAttempts,
        @NotNull Duration staleTimeout,
        @NotEmpty List<Duration> backoff) {

    @AssertTrue(message = "stale-timeout and every backoff duration must be positive")
    public boolean hasPositiveDurations() {
        return staleTimeout != null
                && !staleTimeout.isZero()
                && !staleTimeout.isNegative()
                && backoff != null
                && backoff.stream().allMatch(value -> value != null && !value.isZero() && !value.isNegative());
    }

    public Duration backoffForAttempt(int attempt) {
        return backoff.get(Math.min(Math.max(attempt - 1, 0), backoff.size() - 1));
    }
}
