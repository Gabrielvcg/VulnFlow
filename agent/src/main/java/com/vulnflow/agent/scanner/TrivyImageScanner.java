package com.vulnflow.agent.scanner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vulnflow.agent.shared.AtomicFiles;
import com.vulnflow.agent.shared.SafeErrors;
import com.vulnflow.agent.target.ScanTarget;
import com.vulnflow.agent.target.TargetType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class TrivyImageScanner implements VulnerabilityScanner {

    private static final int OUTPUT_CAPTURE_LIMIT = 16 * 1024;
    private final Path trivyPath;
    private final Path temporaryDirectory;
    private final Duration timeout;
    private final long maxReportBytes;
    private final CommandExecutor commandExecutor;
    private final ObjectMapper objectMapper;

    public TrivyImageScanner(
            Path trivyPath,
            Path temporaryDirectory,
            Duration timeout,
            long maxReportBytes,
            CommandExecutor commandExecutor,
            ObjectMapper objectMapper) {
        this.trivyPath = trivyPath;
        this.temporaryDirectory = temporaryDirectory.toAbsolutePath().normalize();
        this.timeout = timeout;
        this.maxReportBytes = maxReportBytes;
        this.commandExecutor = commandExecutor;
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(this.temporaryDirectory);
        } catch (IOException exception) {
            throw new ScanException("Agent temporary directory could not be created", exception);
        }
    }

    @Override
    public String verifyAvailable() {
        CommandResult result = commandExecutor.execute(
                List.of(trivyPath.toString(), "--version"),
                Duration.ofMillis(Math.max(1, Math.min(timeout.toMillis(), 30_000))),
                OUTPUT_CAPTURE_LIMIT);
        if (result.timedOut()) {
            throw new ScanException("Trivy version check timed out");
        }
        if (result.exitCode() != 0) {
            throw new ScanException("Trivy is unavailable or its version check failed");
        }
        String version = result.stdout() == null ? "" : result.stdout().trim();
        return version.isBlank() ? "Trivy available" : SafeErrors.limited(version);
    }

    @Override
    public ScanArtifact scan(ScanTarget target) {
        if (target.type() != TargetType.CONTAINER_IMAGE) {
            throw new ScanException("Unsupported scan target type");
        }
        Path output = null;
        try {
            output = Files.createTempFile(temporaryDirectory, "trivy-report-", ".json");
            List<String> arguments = commandArguments(target, output);
            CommandResult result = commandExecutor.execute(arguments, timeout, OUTPUT_CAPTURE_LIMIT);
            if (result.timedOut()) {
                throw new ScanException("Trivy scan timed out");
            }
            if (result.exitCode() != 0) {
                throw new ScanException("Trivy scan failed with exit code " + result.exitCode());
            }
            if (!Files.isRegularFile(output)) {
                throw new ScanException("Trivy did not create a report");
            }
            long size = Files.size(output);
            if (size == 0) {
                throw new ScanException("Trivy created an empty report");
            }
            if (size > maxReportBytes) {
                throw new ScanException("Trivy report exceeds the configured size limit");
            }
            JsonNode report;
            try (InputStream input = Files.newInputStream(output)) {
                report = objectMapper.readTree(input);
                if (report == null) {
                    throw new ScanException("Trivy report is not valid JSON");
                }
            }
            if (report instanceof ObjectNode root) {
                root.remove("CreatedAt");
            }
            AtomicFiles.write(output, objectMapper.writeValueAsBytes(report));
            long normalizedSize = Files.size(output);
            if (normalizedSize > maxReportBytes) {
                throw new ScanException("Trivy report exceeds the configured size limit");
            }
            return new ScanArtifact(output, Instant.now(), normalizedSize);
        } catch (IOException exception) {
            deleteTemporary(output);
            throw new ScanException("Trivy report could not be validated", exception);
        } catch (RuntimeException exception) {
            deleteTemporary(output);
            throw exception;
        }
    }

    public List<String> commandArguments(ScanTarget target, Path output) {
        return List.of(
                trivyPath.toString(),
                "image",
                "--scanners", "vuln",
                "--format", "json",
                "--output", output.toString(),
                target.reference());
    }

    private void deleteTemporary(Path output) {
        if (output == null) {
            return;
        }
        try {
            Files.deleteIfExists(output);
        } catch (IOException ignored) {
            // The primary scan failure remains the actionable error.
        }
    }
}
