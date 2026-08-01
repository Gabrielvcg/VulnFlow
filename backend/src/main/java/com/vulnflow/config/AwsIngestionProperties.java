package com.vulnflow.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

@Validated
@Profile("aws")
@ConfigurationProperties(prefix = "vulnflow.aws")
public record AwsIngestionProperties(
        @NotBlank String region,
        @NotBlank String s3Bucket,
        @NotBlank String s3Prefix,
        @NotBlank String sqsQueueUrl,
        @Positive long maxPayloadBytes,
        Duration apiCallTimeout,
        Duration connectionTimeout) {
}
