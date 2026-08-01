package com.vulnflow.config;

import com.vulnflow.aws.messaging.SqsIngestionMessagePublisher;
import com.vulnflow.aws.dynamodb.DynamoDbProcessingResultStore;
import com.vulnflow.aws.storage.S3ReportStorage;
import com.vulnflow.contract.IngestionEventJsonCodec;
import com.vulnflow.processing.port.IngestionMessagePublisher;
import com.vulnflow.processing.port.ReportStorage;
import com.vulnflow.processing.port.ProcessingResultReader;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration
@Profile("aws")
@EnableConfigurationProperties(AwsIngestionProperties.class)
public class AwsAdapterConfiguration {
    @Bean
    DefaultCredentialsProvider awsCredentialsProvider() {
        return DefaultCredentialsProvider.create();
    }

    @Bean
    S3Client s3Client(AwsIngestionProperties properties, AwsCredentialsProvider credentialsProvider) {
        return S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentialsProvider)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(requiredDuration(properties.connectionTimeout(), "connectionTimeout"))
                        .socketTimeout(requiredDuration(properties.apiCallTimeout(), "apiCallTimeout")))
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(requiredDuration(properties.apiCallTimeout(), "apiCallTimeout"))
                        .retryStrategy(RetryMode.STANDARD))
                .build();
    }

    @Bean
    SqsClient sqsClient(AwsIngestionProperties properties, AwsCredentialsProvider credentialsProvider) {
        return SqsClient.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentialsProvider)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(requiredDuration(properties.connectionTimeout(), "connectionTimeout"))
                        .socketTimeout(requiredDuration(properties.apiCallTimeout(), "apiCallTimeout")))
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(requiredDuration(properties.apiCallTimeout(), "apiCallTimeout"))
                        .retryStrategy(RetryMode.STANDARD))
                .build();
    }

    @Bean
    DynamoDbClient dynamoDbClient(AwsIngestionProperties properties, AwsCredentialsProvider credentialsProvider) {
        return DynamoDbClient.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentialsProvider)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(requiredDuration(properties.connectionTimeout(), "connectionTimeout"))
                        .socketTimeout(requiredDuration(properties.apiCallTimeout(), "apiCallTimeout")))
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(requiredDuration(properties.apiCallTimeout(), "apiCallTimeout"))
                        .retryStrategy(RetryMode.STANDARD))
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

    @Bean
    ProcessingResultReader processingResultReader(DynamoDbClient client, AwsIngestionProperties properties) {
        return new DynamoDbProcessingResultStore(
                client,
                properties.dynamodbTable(),
                properties.dynamodbMaxFindings());
    }

    private java.time.Duration requiredDuration(java.time.Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
