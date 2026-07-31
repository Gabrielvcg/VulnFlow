package com.vulnflow.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.vulnflow.agent.target.ScanTarget;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgentConfigLoader {

    private static final long MAX_TARGET_FILE_BYTES = 1024 * 1024;
    private static final Pattern SIMPLE_DURATION = Pattern.compile("^(\\d+)(ms|s|m|h|d)$");
    private static final Pattern SIZE = Pattern.compile("^(\\d+)(B|KB|MB|GB)?$", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public AgentConfig load(Map<String, String> environment) {
        URI apiUrl = parseApiUrl(required(environment, "VULNFLOW_API_URL"));
        String apiKey = required(environment, "VULNFLOW_API_KEY");
        String agentId = required(environment, "VULNFLOW_AGENT_ID");
        Duration scanInterval = duration(environment, "VULNFLOW_SCAN_INTERVAL", "1h");
        Path trivyPath = Path.of(value(environment, "VULNFLOW_TRIVY_PATH", "trivy"));
        Path dataDirectory = normalizedPath(environment, "VULNFLOW_AGENT_DATA_DIR", "./data/agent");
        Path temporaryDirectory = normalizedPath(
                environment, "VULNFLOW_AGENT_TEMP_DIR", dataDirectory.resolve("tmp").toString());
        int maxConcurrentScans = positiveInt(environment, "VULNFLOW_AGENT_MAX_CONCURRENT_SCANS", "1");
        Duration uploadRetryInterval = duration(
                environment, "VULNFLOW_AGENT_UPLOAD_RETRY_INTERVAL", "30s");
        Duration trivyTimeout = duration(environment, "VULNFLOW_TRIVY_TIMEOUT", "15m");
        long maxReportBytes = size(environment, "VULNFLOW_AGENT_MAX_REPORT_SIZE", "10MB");
        long maxOutboxBytes = size(environment, "VULNFLOW_AGENT_MAX_OUTBOX_BYTES", "1GB");
        int maxOutboxItems = positiveInt(environment, "VULNFLOW_AGENT_MAX_OUTBOX_ITEMS", "1000");
        Duration uploadedRetention = duration(environment, "VULNFLOW_AGENT_UPLOADED_RETENTION", "7d");
        Duration httpConnectTimeout = duration(environment, "VULNFLOW_AGENT_HTTP_CONNECT_TIMEOUT", "10s");
        Duration httpRequestTimeout = duration(environment, "VULNFLOW_AGENT_HTTP_REQUEST_TIMEOUT", "2m");
        Duration shutdownTimeout = duration(environment, "VULNFLOW_AGENT_SHUTDOWN_TIMEOUT", "30s");
        Path targetsFile = normalizedPath(environment, "VULNFLOW_TARGETS_FILE", "./targets.yml");
        List<ScanTarget> targets = readTargets(targetsFile);
        validateTargets(targets);
        return new AgentConfig(
                apiUrl,
                apiKey,
                agentId,
                scanInterval,
                trivyPath,
                dataDirectory,
                temporaryDirectory,
                maxConcurrentScans,
                uploadRetryInterval,
                trivyTimeout,
                maxReportBytes,
                maxOutboxBytes,
                maxOutboxItems,
                uploadedRetention,
                httpConnectTimeout,
                httpRequestTimeout,
                shutdownTimeout,
                targetsFile,
                targets);
    }

    private List<ScanTarget> readTargets(Path targetsFile) {
        if (!Files.isRegularFile(targetsFile)) {
            throw new AgentConfigurationException("Targets file does not exist: " + targetsFile);
        }
        try {
            if (Files.size(targetsFile) > MAX_TARGET_FILE_BYTES) {
                throw new AgentConfigurationException("Targets file exceeds the 1 MiB limit");
            }
            TargetDocument document = yamlMapper.readValue(targetsFile.toFile(), TargetDocument.class);
            return document == null || document.targets() == null ? List.of() : document.targets();
        } catch (IOException exception) {
            throw new AgentConfigurationException("Targets file could not be read", exception);
        }
    }

    private void validateTargets(List<ScanTarget> targets) {
        if (targets.isEmpty()) {
            throw new AgentConfigurationException("At least one target must be configured");
        }
        Set<String> identities = new HashSet<>();
        for (ScanTarget target : targets) {
            if (target == null || target.type() == null) {
                throw new AgentConfigurationException("Every target requires a supported type");
            }
            if (target.name() == null || target.name().isBlank()) {
                throw new AgentConfigurationException("Every target requires a name");
            }
            if (target.name().length() > 255) {
                throw new AgentConfigurationException("Target names cannot exceed 255 characters");
            }
            if (target.reference() == null || target.reference().isBlank()) {
                throw new AgentConfigurationException("Every target requires a reference");
            }
            if (target.reference().length() > 500) {
                throw new AgentConfigurationException("Target references cannot exceed 500 characters");
            }
            if (!identities.add(target.stableKey())) {
                throw new AgentConfigurationException("Duplicate target identity: " + target.name());
            }
        }
    }

    private URI parseApiUrl(String value) {
        try {
            URI uri = URI.create(value.endsWith("/") ? value : value + "/");
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new AgentConfigurationException("VULNFLOW_API_URL must be an HTTP(S) base URL");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new AgentConfigurationException("VULNFLOW_API_URL is invalid", exception);
        }
    }

    private String required(Map<String, String> environment, String key) {
        String result = environment.get(key);
        if (result == null || result.isBlank()) {
            throw new AgentConfigurationException(key + " must be configured");
        }
        return result.trim();
    }

    private String value(Map<String, String> environment, String key, String defaultValue) {
        String result = environment.get(key);
        return result == null || result.isBlank() ? defaultValue : result.trim();
    }

    private Path normalizedPath(Map<String, String> environment, String key, String defaultValue) {
        return Path.of(value(environment, key, defaultValue)).toAbsolutePath().normalize();
    }

    private int positiveInt(Map<String, String> environment, String key, String defaultValue) {
        try {
            int result = Integer.parseInt(value(environment, key, defaultValue));
            if (result <= 0) {
                throw new NumberFormatException("not positive");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new AgentConfigurationException(key + " must be a positive integer", exception);
        }
    }

    private Duration duration(Map<String, String> environment, String key, String defaultValue) {
        String raw = value(environment, key, defaultValue).toLowerCase(Locale.ROOT);
        try {
            Duration result;
            Matcher matcher = SIMPLE_DURATION.matcher(raw);
            if (matcher.matches()) {
                long amount = Long.parseLong(matcher.group(1));
                result = switch (matcher.group(2)) {
                    case "ms" -> Duration.ofMillis(amount);
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    case "h" -> Duration.ofHours(amount);
                    case "d" -> Duration.ofDays(amount);
                    default -> throw new IllegalArgumentException("Unsupported duration unit");
                };
            } else {
                result = Duration.parse(raw.toUpperCase(Locale.ROOT));
            }
            if (result.isZero() || result.isNegative()) {
                throw new IllegalArgumentException("not positive");
            }
            return result;
        } catch (RuntimeException exception) {
            throw new AgentConfigurationException(key + " must be a positive duration", exception);
        }
    }

    private long size(Map<String, String> environment, String key, String defaultValue) {
        String raw = value(environment, key, defaultValue).toUpperCase(Locale.ROOT);
        Matcher matcher = SIZE.matcher(raw);
        if (!matcher.matches()) {
            throw new AgentConfigurationException(key + " must be a positive byte size");
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            long multiplier = switch (matcher.group(2) == null ? "B" : matcher.group(2)) {
                case "B" -> 1L;
                case "KB" -> 1024L;
                case "MB" -> 1024L * 1024L;
                case "GB" -> 1024L * 1024L * 1024L;
                default -> throw new IllegalArgumentException("Unsupported size unit");
            };
            long result = Math.multiplyExact(amount, multiplier);
            if (result <= 0) {
                throw new IllegalArgumentException("not positive");
            }
            return result;
        } catch (RuntimeException exception) {
            throw new AgentConfigurationException(key + " must be a positive byte size", exception);
        }
    }

    private record TargetDocument(List<ScanTarget> targets) {
    }
}
