package com.vulnflow.agent.scheduling;

import com.vulnflow.agent.outbox.AgentOutbox;
import com.vulnflow.agent.scanner.ScanArtifact;
import com.vulnflow.agent.scanner.VulnerabilityScanner;
import com.vulnflow.agent.target.ScanTarget;
import com.vulnflow.agent.target.TargetRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScanCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScanCoordinator.class);
    private final String agentId;
    private final TargetRegistry targetRegistry;
    private final VulnerabilityScanner scanner;
    private final AgentOutbox outbox;
    private final AgentStateStore stateStore;
    private final ExecutorService scanExecutor;
    private final AtomicBoolean cycleRunning = new AtomicBoolean();
    private final Set<String> targetsRunning = ConcurrentHashMap.newKeySet();

    public ScanCoordinator(
            String agentId,
            TargetRegistry targetRegistry,
            VulnerabilityScanner scanner,
            AgentOutbox outbox,
            AgentStateStore stateStore,
            ExecutorService scanExecutor) {
        this.agentId = agentId;
        this.targetRegistry = targetRegistry;
        this.scanner = scanner;
        this.outbox = outbox;
        this.stateStore = stateStore;
        this.scanExecutor = scanExecutor;
    }

    public void runCycle() {
        if (!cycleRunning.compareAndSet(false, true)) {
            LOGGER.info("event=scan_cycle_skipped agentId={} result=already_running", agentId);
            return;
        }
        Instant cycleAt = Instant.now();
        try {
            List<Future<?>> scans = new ArrayList<>();
            for (ScanTarget target : targetRegistry.targets()) {
                if (targetsRunning.add(target.stableKey())) {
                    scans.add(scanExecutor.submit(() -> scanTarget(target)));
                } else {
                    LOGGER.info(
                            "event=scan_skipped agentId={} target={} result=already_running",
                            agentId, target.name());
                }
            }
            for (Future<?> scan : scans) {
                try {
                    scan.get();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.ExecutionException exception) {
                    LOGGER.error("event=scan_task_failed agentId={} result=isolated_failure", agentId);
                }
            }
        } finally {
            stateStore.recordCycle(cycleAt);
            cycleRunning.set(false);
        }
    }

    private void scanTarget(ScanTarget target) {
        try (ScanArtifact artifact = scanner.scan(target)) {
            var item = outbox.enqueue(agentId, target, artifact.scannedAt(), artifact.path());
            stateStore.recordScan(target.name(), artifact.scannedAt());
            LOGGER.info(
                    "event=scan_completed agentId={} target={} scanLocalId={} outboxItemId={} result=stored",
                    agentId, target.name(), item.id(), item.id());
        } catch (RuntimeException | java.io.IOException exception) {
            LOGGER.error(
                    "event=scan_failed agentId={} target={} result={} errorType={}",
                    agentId, target.name(), "isolated", exception.getClass().getSimpleName());
        } finally {
            targetsRunning.remove(target.stableKey());
        }
    }
}
