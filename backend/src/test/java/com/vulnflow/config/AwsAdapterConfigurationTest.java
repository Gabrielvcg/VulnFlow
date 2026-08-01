package com.vulnflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.vulnflow.ingestion.LocalFileReportStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

class AwsAdapterConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(AwsAdapterConfiguration.class);

    @Test
    void awsClientsAreNotCreatedWithoutTheAwsProfileOrCredentials() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(S3Client.class);
            assertThat(context).doesNotHaveBean(SqsClient.class);
            assertThat(context).doesNotHaveBean(AwsIngestionProperties.class);
        });
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
