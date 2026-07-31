package com.vulnflow.agent.scheduling;

import java.time.Instant;
import java.util.Map;

public record AgentState(
        Instant lastCycle,
        Map<String, Instant> lastScanByTarget,
        Instant lastSuccessfulUpload) {

    public static AgentState empty() {
        return new AgentState(null, Map.of(), null);
    }
}
