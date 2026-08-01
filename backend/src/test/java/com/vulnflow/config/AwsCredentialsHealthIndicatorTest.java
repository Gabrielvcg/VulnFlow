package com.vulnflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

class AwsCredentialsHealthIndicatorTest {
    @Test
    void reportsUpOnlyForTemporarySessionCredentials() {
        var indicator = new AwsCredentialsHealthIndicator(() -> AwsSessionCredentials.create(
                "temporary-access-id", "temporary-secret", "temporary-session-token"));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("credentialType", "temporary-session");
    }

    @Test
    void rejectsLongLivedBasicCredentials() {
        var indicator = new AwsCredentialsHealthIndicator(() -> AwsBasicCredentials.create(
                "persistent-access-id", "persistent-secret"));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("credentialType", "temporary-session-required");
    }

    @Test
    void reportsUnavailableWithoutExposingProviderFailure() {
        var indicator = new AwsCredentialsHealthIndicator(() -> {
            throw new IllegalStateException("sensitive provider detail");
        });

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("credentialType", "unavailable")
                .doesNotContainValue("sensitive provider detail");
    }
}
