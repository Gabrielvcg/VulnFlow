package com.vulnflow.ingestion;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vulnflow.report-storage")
public record ReportStorageProperties(Path directory) {
}
