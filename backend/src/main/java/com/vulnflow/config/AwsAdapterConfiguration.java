package com.vulnflow.config;

import com.vulnflow.aws.messaging.SqsIngestionMessagePublisher;
import com.vulnflow.aws.storage.S3ReportStorage;
import com.vulnflow.contract.IngestionEventJsonCodec;
import com.vulnflow.processing.port.IngestionMessagePublisher;
import com.vulnflow.processing.port.ReportStorage;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@Profile("aws")
@EnableConfigurationProperties(AwsIngestionProperties.class)
public class AwsAdapterConfiguration {
    @Bean
    S3Client s3Client(AwsIngestionProperties properties) {
        return S3Client.builder()
                .region(Region.of(properties.region()))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(requiredDuration(properties.connectionTimeout(), "connectionTimeout"))
                        .socketTimeout(requiredDuration(properties.apiCallTimeout(), "apiCallTimeout")))
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(requiredDuration(properties.apiCallTimeout(), "apiCallTimeout")))
                .build();
    }

    @Bean
    SqsClient sqsClient(AwsIngestionProperties properties) {
        return SqsClient.builder()
                .region(Region.of(properties.region()))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(requiredDuration(properties.connectionTimeout(), "connectionTimeout"))
                        .socketTimeout(requiredDuration(properties.apiCallTimeout(), "apiCallTimeout")))
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(requiredDuration(properties.apiCallTimeout(), "apiCallTimeout")))
                .build();
    }

    @Bean
    ReportStorage awsReportStorage(S3Client client, AwsIngestionProperties properties) {
        return new S3ReportStorage(
                client, properties.s3Bucket(), properties.s3Prefix(), properties.maxPayloadBytes());
    }

    @Bean
    IngestionMessagePublisher ingestionMessagePublisher(SqsClient client, AwsIngestionProperties properties) {
        return new SqsIngestionMessagePublisher(
                client, properties.sqsQueueUrl(), new IngestionEventJsonCodec());
    }

    private java.time.Duration requiredDuration(java.time.Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
