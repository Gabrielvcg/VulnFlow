package com.vulnflow.aws.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnflow.aws.dynamodb.DynamoDbProcessingResultStore;
import com.vulnflow.aws.storage.S3ReportStorage;
import com.vulnflow.contract.IngestionEventJsonCodec;
import com.vulnflow.contract.IngestionEventV1;
import com.vulnflow.processing.DefaultFindingRiskCalculator;
import com.vulnflow.processing.PayloadIntegrityVerifier;
import com.vulnflow.processing.TrivyVulnerabilityReportParser;
import com.vulnflow.processing.VulnerabilityReportProcessor;
import com.vulnflow.processing.port.ProcessingResultStore;
import com.vulnflow.processing.port.ReportStorage;
import java.time.Duration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

final class DefaultLambdaWiring {
    private DefaultLambdaWiring() {
    }

    static Components create() {
        int timeoutSeconds = positiveInt("VULNFLOW_AWS_API_TIMEOUT_SECONDS", 10);
        long maxPayloadBytes = positiveLong("VULNFLOW_MAX_PAYLOAD_BYTES", 10L * 1024 * 1024);
        int maxDescriptionLength = positiveInt("VULNFLOW_MAX_DESCRIPTION_LENGTH", 20_000);
        int maximumFindings = positiveInt("VULNFLOW_DYNAMODB_MAX_FINDINGS", 10_000);
        Region region = Region.of(environment("AWS_REGION", "eu-west-1"));
        UrlConnectionHttpClient.Builder httpClient = UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(timeoutSeconds))
                .socketTimeout(Duration.ofSeconds(timeoutSeconds));

        S3Client s3Client = S3Client.builder()
                .region(region)
                .httpClientBuilder(httpClient)
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(Duration.ofSeconds(timeoutSeconds))
                        .retryStrategy(RetryMode.STANDARD))
                .build();
        DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
                .region(region)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(timeoutSeconds))
                        .socketTimeout(Duration.ofSeconds(timeoutSeconds)))
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(Duration.ofSeconds(timeoutSeconds))
                        .retryStrategy(RetryMode.STANDARD))
                .build();

        ReportStorage storage = new S3ReportStorage(
                s3Client, required("VULNFLOW_S3_BUCKET"), environment("VULNFLOW_S3_PREFIX", "reports"), maxPayloadBytes);
        ProcessingResultStore<IngestionEventV1> resultStore = new DynamoDbProcessingResultStore(
                dynamoDbClient, required("VULNFLOW_DYNAMODB_TABLE"), maximumFindings);
        IngestionEventJsonCodec codec = new IngestionEventJsonCodec();
        VulnerabilityReportProcessor processor = new VulnerabilityReportProcessor(
                new PayloadIntegrityVerifier(),
                new TrivyVulnerabilityReportParser(new ObjectMapper(), maxDescriptionLength),
                new DefaultFindingRiskCalculator());
        return new Components(codec, storage, processor, resultStore);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int positiveInt(String name, int fallback) {
        long value = positiveLong(name, fallback);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalStateException(name + " exceeds the supported range");
        }
        return (int) value;
    }

    private static long positiveLong(String name, long fallback) {
        String raw = System.getenv(name);
        long value = raw == null || raw.isBlank() ? fallback : Long.parseLong(raw);
        if (value < 1) {
            throw new IllegalStateException(name + " must be positive");
        }
        return value;
    }

    record Components(
            IngestionEventJsonCodec codec,
            ReportStorage reportStorage,
            VulnerabilityReportProcessor processor,
            ProcessingResultStore<IngestionEventV1> resultStore) {
    }
}
