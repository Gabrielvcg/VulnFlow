package com.vulnflow.ingestion;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "vulnflow.report-storage")
public record ReportStorageProperties(@NotNull Path directory) {

    @AssertTrue(message = "directory must be configured")
    public boolean isDirectoryConfigured() {
        return directory != null && !directory.toString().isBlank();
    }
}
