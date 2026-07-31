package com.vulnflow.ingestion;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LocalFileReportStorage implements ReportStorage {

    private final Path baseDirectory;

    public LocalFileReportStorage(ReportStorageProperties properties) {
        this.baseDirectory = properties.directory().toAbsolutePath().normalize();
    }

    @PostConstruct
    public void initialize() {
        try {
            Files.createDirectories(baseDirectory);
        } catch (IOException exception) {
            throw new ReportStorageException("The report storage directory could not be initialized", exception);
        }
    }

    @Override
    public String store(UUID scanId, byte[] content) {
        String payloadKey = scanId + "/" + UUID.randomUUID() + ".json";
        Path target = resolve(payloadKey);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "report-", ".tmp");
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
            moveIntoPlace(temporary, target);
            temporary = null;
            return payloadKey;
        } catch (IOException exception) {
            throw new ReportStorageException("The report payload could not be stored", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The original storage exception remains the relevant failure.
                }
            }
        }
    }

    @Override
    public byte[] load(String payloadKey) {
        Path payload = resolve(payloadKey);
        try {
            return Files.readAllBytes(payload);
        } catch (NoSuchFileException exception) {
            throw new PayloadNotFoundException("The stored report payload does not exist");
        } catch (IOException exception) {
            throw new TransientReportStorageException("The report payload could not be read", exception);
        }
    }

    @Override
    public void delete(String payloadKey) {
        Path payload = resolve(payloadKey);
        try {
            Files.deleteIfExists(payload);
            Path parent = payload.getParent();
            if (!parent.equals(baseDirectory)) {
                try {
                    Files.delete(parent);
                } catch (IOException ignored) {
                    // A non-empty scan directory is valid and can be retained.
                }
            }
        } catch (IOException exception) {
            throw new ReportStorageException("The report payload could not be deleted", exception);
        }
    }

    @Override
    public boolean exists(String payloadKey) {
        return Files.isRegularFile(resolve(payloadKey));
    }

    private void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private Path resolve(String payloadKey) {
        if (payloadKey == null || payloadKey.isBlank()) {
            throw new IllegalArgumentException("payloadKey must not be blank");
        }
        Path relative = Path.of(payloadKey);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Absolute payload keys are not allowed");
        }
        Path resolved = baseDirectory.resolve(relative).normalize();
        if (!resolved.startsWith(baseDirectory)) {
            throw new IllegalArgumentException("The payload key escapes the storage directory");
        }
        return resolved;
    }
}
