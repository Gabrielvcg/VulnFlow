package com.vulnflow.ingestion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "vulnflow.ingestion")
public record IngestionProperties(
        DataSize maxFileSize,
        Duration processingTimeout,
        int maxDescriptionLength) {
}
