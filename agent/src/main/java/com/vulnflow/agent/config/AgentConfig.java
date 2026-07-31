package com.vulnflow.agent.config;

import com.vulnflow.agent.target.ScanTarget;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AgentConfig(
        URI apiUrl,
        String apiKey,
        String agentId,
        Duration scanInterval,
        Path trivyPath,
        Path dataDirectory,
        Path temporaryDirectory,
        int maxConcurrentScans,
        Duration uploadRetryInterval,
        Duration trivyTimeout,
        long maxReportBytes,
        long maxOutboxBytes,
        int maxOutboxItems,
        Duration uploadedRetention,
        Duration httpConnectTimeout,
        Duration httpRequestTimeout,
        Duration shutdownTimeout,
        Path targetsFile,
        List<ScanTarget> targets) {

    public AgentConfig {
        targets = List.copyOf(targets);
    }

    public Map<String, Object> safeView() {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("apiUrl", apiUrl.toString());
        safe.put("apiKeyConfigured", apiKey != null && !apiKey.isBlank());
        safe.put("agentId", agentId);
        safe.put("scanInterval", scanInterval.toString());
        safe.put("trivyPath", trivyPath.toString());
        safe.put("dataDirectory", dataDirectory.toString());
        safe.put("temporaryDirectory", temporaryDirectory.toString());
        safe.put("maxConcurrentScans", maxConcurrentScans);
        safe.put("uploadRetryInterval", uploadRetryInterval.toString());
        safe.put("trivyTimeout", trivyTimeout.toString());
        safe.put("maxReportBytes", maxReportBytes);
        safe.put("maxOutboxBytes", maxOutboxBytes);
        safe.put("maxOutboxItems", maxOutboxItems);
        safe.put("uploadedRetention", uploadedRetention.toString());
        safe.put("httpConnectTimeout", httpConnectTimeout.toString());
        safe.put("httpRequestTimeout", httpRequestTimeout.toString());
        safe.put("shutdownTimeout", shutdownTimeout.toString());
        safe.put("targetsFile", targetsFile.toString());
        safe.put("targets", targets);
        return Map.copyOf(safe);
    }
}
