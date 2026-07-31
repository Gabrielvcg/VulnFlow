package com.vulnflow.agent.scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public record ScanArtifact(Path path, Instant scannedAt, long sizeBytes) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(path);
    }
}
