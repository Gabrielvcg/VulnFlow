package com.vulnflow.agent.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnflow.agent.shared.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;

public class AgentStateStore {

    private final Path stateFile;
    private final ObjectMapper objectMapper;
    private AgentState state;

    public AgentStateStore(Path dataDirectory, ObjectMapper objectMapper) {
        this.stateFile = dataDirectory.toAbsolutePath().normalize().resolve("state.json");
        this.objectMapper = objectMapper;
        this.state = load();
    }

    public synchronized AgentState current() {
        return state;
    }

    public synchronized void recordCycle(Instant at) {
        state = new AgentState(at, state.lastScanByTarget(), state.lastSuccessfulUpload());
        persist();
    }

    public synchronized void recordScan(String targetName, Instant at) {
        var scans = new LinkedHashMap<>(state.lastScanByTarget());
        scans.put(targetName, at);
        state = new AgentState(state.lastCycle(), java.util.Map.copyOf(scans), state.lastSuccessfulUpload());
        persist();
    }

    public synchronized void recordSuccessfulUpload(Instant at) {
        state = new AgentState(state.lastCycle(), state.lastScanByTarget(), at);
        persist();
    }

    private AgentState load() {
        if (!Files.exists(stateFile)) {
            return AgentState.empty();
        }
        try {
            return objectMapper.readValue(stateFile.toFile(), AgentState.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Agent state file could not be read", exception);
        }
    }

    private void persist() {
        try {
            AtomicFiles.write(stateFile, objectMapper.writeValueAsBytes(state));
        } catch (IOException exception) {
            throw new IllegalStateException("Agent state file could not be updated", exception);
        }
    }
}
