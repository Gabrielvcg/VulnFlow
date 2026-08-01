package com.vulnflow.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

@Validated
@Profile("aws")
@ConfigurationProperties(prefix = "vulnflow.aws")
public record AwsIngestionProperties(
        @NotBlank String region,
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") String s3Bucket,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]{0,511}") String s3Prefix,
        @NotBlank @Pattern(regexp = "https://sqs[.-][A-Za-z0-9-]+\\.amazonaws\\.com(?:\\.cn)?/\\d{12}/[A-Za-z0-9_-]{1,80}") String sqsQueueUrl,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,255}") String dynamodbTable,
        @Min(1) @Max(100000) int dynamodbMaxFindings,
        @Positive long maxPayloadBytes,
        Duration apiCallTimeout,
        Duration connectionTimeout) {

    @AssertTrue(message = "s3-bucket and s3-prefix must be safe logical identifiers")
    public boolean hasSafeS3Location() {
        if (s3Bucket == null || s3Prefix == null) {
            return true;
        }
        boolean ipAddress = s3Bucket.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
        boolean safeBucket = !ipAddress && !s3Bucket.contains("..") && !s3Bucket.contains(".-")
                && !s3Bucket.contains("-.");
        boolean safePrefix = !s3Prefix.startsWith("/")
                && !s3Prefix.contains("..")
                && !s3Prefix.contains("//")
                && !s3Prefix.contains("\\");
        return safeBucket && safePrefix;
    }
}
