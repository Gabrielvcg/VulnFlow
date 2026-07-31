package com.vulnflow.aws.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.ServiceLoader;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;

final class DefaultLambdaWiring {
    private DefaultLambdaWiring() {
    }

    static Components create() {
        int timeoutSeconds = positiveInt("VULNFLOW_AWS_API_TIMEOUT_SECONDS", 10);
        long maxPayloadBytes = positiveLong("VULNFLOW_MAX_PAYLOAD_BYTES", 10L * 1024 * 1024);
        int maxDescriptionLength = positiveInt("VULNFLOW_MAX_DESCRIPTION_LENGTH", 20_000);
        S3Client s3Client = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(timeoutSeconds))
                        .socketTimeout(Duration.ofSeconds(timeoutSeconds)))
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(Duration.ofSeconds(timeoutSeconds)))
                .build();
        ReportStorage storage = new S3ReportStorage(
                s3Client, required("VULNFLOW_S3_BUCKET"), environment("VULNFLOW_S3_PREFIX", "reports"), maxPayloadBytes);
        IngestionEventJsonCodec codec = new IngestionEventJsonCodec();
        VulnerabilityReportProcessor processor = new VulnerabilityReportProcessor(
                new PayloadIntegrityVerifier(),
                new TrivyVulnerabilityReportParser(new ObjectMapper(), maxDescriptionLength),
                new DefaultFindingRiskCalculator());
        return new Components(codec, storage, processor, loadResultStore());
    }

    private static ProcessingResultStore<IngestionEventV1> loadResultStore() {
        List<LambdaProcessingResultStoreProvider> providers = ServiceLoader
                .load(LambdaProcessingResultStoreProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        if (providers.size() != 1) {
            throw new IllegalStateException(
                    "Exactly one LambdaProcessingResultStoreProvider must be packaged after the result-storage ADR is implemented");
        }
        return providers.get(0).create();
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
