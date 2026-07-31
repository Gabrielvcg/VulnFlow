package com.vulnflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class IngestionConfigurationValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsValidConfiguration() {
        WorkerProperties worker = new WorkerProperties(
                true, Duration.ofSeconds(1), 1, 3, Duration.ofMinutes(1), List.of(Duration.ofSeconds(1)));

        assertThat(validator.validate(worker)).isEmpty();
        assertThat(validator.validate(new IngestionProperties(DataSize.ofMegabytes(1), 1))).isEmpty();
        assertThat(validator.validate(new ReportStorageProperties(Path.of("reports")))).isEmpty();
    }

    @Test
    void rejectsNonPositiveWorkerDurationsAndCounters() {
        WorkerProperties worker = new WorkerProperties(
                true,
                Duration.ZERO,
                0,
                0,
                Duration.ofSeconds(-1),
                List.of(Duration.ZERO));

        assertThat(validator.validate(worker))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("pollInterval", "batchSize", "maxAttempts", "staleTimeout", "backoff[0].<list element>");
    }

    @Test
    void rejectsEmptyBackoffAndInvalidIngestionOrStorageValues() {
        WorkerProperties worker = new WorkerProperties(
                true, Duration.ofSeconds(1), 1, 1, Duration.ofSeconds(1), List.of());

        assertThat(validator.validate(worker))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("backoff");
        assertThat(validator.validate(new IngestionProperties(DataSize.ofBytes(0), 0))).hasSize(2);
        assertThat(validator.validate(new ReportStorageProperties(Path.of(""))))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("directoryConfigured");
    }
}
