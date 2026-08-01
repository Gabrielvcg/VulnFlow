package com.vulnflow.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

@Component("awsCredentials")
@Profile("aws")
public final class AwsCredentialsHealthIndicator implements HealthIndicator {
    private final AwsCredentialsProvider credentialsProvider;

    public AwsCredentialsHealthIndicator(AwsCredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
    }

    @Override
    public Health health() {
        try {
            AwsCredentials credentials = credentialsProvider.resolveCredentials();
            if (credentials instanceof AwsSessionCredentials sessionCredentials
                    && !sessionCredentials.sessionToken().isBlank()) {
                return Health.up().withDetail("credentialType", "temporary-session").build();
            }
            return Health.down().withDetail("credentialType", "temporary-session-required").build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("credentialType", "unavailable").build();
        }
    }
}
