package com.vulnflow.agent.scheduling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnflow.agent.shared.AtomicFiles;
import com.vulnflow.agent.target.ScanTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class AssetCache {

    private static final TypeReference<Map<String, UUID>> CACHE_TYPE = new TypeReference<>() { };
    private final Path cacheFile;
    private final ObjectMapper objectMapper;
    private final Map<String, UUID> assets;

    public AssetCache(Path dataDirectory, ObjectMapper objectMapper) {
        this.cacheFile = dataDirectory.toAbsolutePath().normalize().resolve("asset-cache.json");
        this.objectMapper = objectMapper;
        this.assets = load();
    }

    public synchronized Optional<UUID> find(ScanTarget target) {
        return Optional.ofNullable(assets.get(target.stableKey()));
    }

    public synchronized void put(ScanTarget target, UUID assetId) {
        assets.put(target.stableKey(), assetId);
        persist();
    }

    public synchronized void invalidate(ScanTarget target) {
        if (assets.remove(target.stableKey()) != null) {
            persist();
        }
    }

    private Map<String, UUID> load() {
        if (!Files.exists(cacheFile)) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(cacheFile.toFile(), CACHE_TYPE));
        } catch (IOException exception) {
            throw new IllegalStateException("Agent asset cache could not be read", exception);
        }
    }

    private void persist() {
        try {
            AtomicFiles.write(cacheFile, objectMapper.writeValueAsBytes(assets));
        } catch (IOException exception) {
            throw new IllegalStateException("Agent asset cache could not be updated", exception);
        }
    }
}
