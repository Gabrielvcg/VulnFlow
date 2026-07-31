package com.vulnflow.agent.scheduling;

import com.vulnflow.agent.outbox.OutboxStats;
import java.time.Instant;
import java.util.Map;

public record AgentStatus(
        Instant lastCycle,
        Map<String, Instant> lastScanByTarget,
        long pending,
        long retrying,
        long uploading,
        long deadLetters,
        long uploaded,
        Instant lastSuccessfulUpload) {

    public static AgentStatus from(AgentState state, OutboxStats stats) {
        return new AgentStatus(
                state.lastCycle(),
                state.lastScanByTarget(),
                stats.pending(),
                stats.retrying(),
                stats.uploading(),
                stats.deadLetters(),
                stats.uploaded(),
                state.lastSuccessfulUpload());
    }
}
