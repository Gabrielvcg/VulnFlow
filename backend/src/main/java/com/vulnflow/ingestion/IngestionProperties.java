package com.vulnflow.ingestion;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "vulnflow.ingestion")
public record IngestionProperties(
        @NotNull DataSize maxFileSize,
        @Min(1) int maxDescriptionLength) {

    @AssertTrue(message = "max-file-size must be positive")
    public boolean isMaxFileSizePositive() {
        return maxFileSize != null && maxFileSize.toBytes() > 0;
    }
}
