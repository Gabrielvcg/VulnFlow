package com.vulnflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.vulnflow.ingestion.LocalFileReportStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import com.vulnflow.processing.port.ProcessingResultReader;

class AwsAdapterConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(AwsAdapterConfiguration.class);

    @Test
    void awsClientsAreNotCreatedWithoutTheAwsProfileOrCredentials() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(S3Client.class);
            assertThat(context).doesNotHaveBean(SqsClient.class);
            assertThat(context).doesNotHaveBean(DynamoDbClient.class);
            assertThat(context).doesNotHaveBean(AwsIngestionProperties.class);
        });
    }

    @Test
    void awsProfileSelectsAllAwsAdaptersWithValidConfiguration() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=aws",
                        "vulnflow.aws.region=eu-west-1",
                        "vulnflow.aws.s3-bucket=vulnflow-demo-reports",
                        "vulnflow.aws.s3-prefix=reports",
                        "vulnflow.aws.sqs-queue-url=https://sqs.eu-west-1.amazonaws.com/123456789012/vulnflow-demo",
                        "vulnflow.aws.dynamodb-table=vulnflow-demo-results",
                        "vulnflow.aws.dynamodb-max-findings=100000",
                        "vulnflow.aws.max-payload-bytes=10485760",
                        "vulnflow.aws.api-call-timeout=2s",
                        "vulnflow.aws.connection-timeout=1s")
                .run(context -> {
                    assertThat(context).hasSingleBean(S3Client.class);
                    assertThat(context).hasSingleBean(SqsClient.class);
                    assertThat(context).hasSingleBean(DynamoDbClient.class);
                    assertThat(context).hasSingleBean(ProcessingResultReader.class);
                });
    }

    @Test
    void awsProfileFailsClearlyWhenRequiredConfigurationIsMissing() {
        contextRunner
                .withPropertyValues("spring.profiles.active=aws")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("AwsIngestionProperties");
                });
    }

    @Test
    void awsProfileRejectsAnAmbiguousS3PrefixBeforeCreatingClients() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=aws",
                        "vulnflow.aws.region=eu-west-1",
                        "vulnflow.aws.s3-bucket=vulnflow-demo-reports",
                        "vulnflow.aws.s3-prefix=reports//incoming",
                        "vulnflow.aws.sqs-queue-url=https://sqs.eu-west-1.amazonaws.com/123456789012/vulnflow-demo",
                        "vulnflow.aws.dynamodb-table=vulnflow-demo-results",
                        "vulnflow.aws.dynamodb-max-findings=100000",
                        "vulnflow.aws.max-payload-bytes=10485760",
                        "vulnflow.aws.api-call-timeout=2s",
                        "vulnflow.aws.connection-timeout=1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void localStorageIsExplicitlyExcludedOnlyFromTheAwsProfile() {
        ProfileAssertions.assertProfileAnnotation(LocalFileReportStorage.class, "!aws");
    }

    private static final class ProfileAssertions {
        private static void assertProfileAnnotation(Class<?> type, String profile) {
            org.springframework.context.annotation.Profile annotation =
                    type.getAnnotation(org.springframework.context.annotation.Profile.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).containsExactly(profile);
        }
    }
}
