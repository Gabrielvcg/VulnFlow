package com.vulnflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class IngestionConfigurationContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "vulnflow.ingestion.max-file-size=10MB",
                    "vulnflow.ingestion.max-description-length=8000",
                    "vulnflow.report-storage.directory=reports",
                    "vulnflow.worker.enabled=true",
                    "vulnflow.worker.poll-interval=2s",
                    "vulnflow.worker.batch-size=5",
                    "vulnflow.worker.max-attempts=3",
                    "vulnflow.worker.stale-timeout=15m",
                    "vulnflow.worker.backoff=5s,30s,2m");

    @Test
    void invalidWorkerConfigurationPreventsContextStartup() {
        contextRunner
                .withPropertyValues("vulnflow.worker.poll-interval=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("vulnflow.worker");
                });
    }

    @Test
    void invalidIngestionConfigurationPreventsContextStartup() {
        contextRunner
                .withPropertyValues("vulnflow.ingestion.max-description-length=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("vulnflow.ingestion");
                });
    }

    @Test
    void blankStorageDirectoryPreventsContextStartup() {
        contextRunner
                .withPropertyValues("vulnflow.report-storage.directory=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("vulnflow.report-storage");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
        IngestionProperties.class,
        ReportStorageProperties.class,
        WorkerProperties.class
    })
    static class PropertiesConfiguration {
    }
}
