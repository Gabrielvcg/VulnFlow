package com.vulnflow.agent.scheduling;

import com.vulnflow.agent.outbox.AgentOutbox;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentScheduler implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentScheduler.class);
    private final String agentId;
    private final ScanCoordinator scanCoordinator;
    private final UploadCoordinator uploadCoordinator;
    private final AgentOutbox outbox;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService scanExecutor;
    private final Duration scanInterval;
    private final Duration uploadInterval;
    private final Duration retention;
    private final Duration shutdownTimeout;

    public AgentScheduler(
            String agentId,
            ScanCoordinator scanCoordinator,
            UploadCoordinator uploadCoordinator,
            AgentOutbox outbox,
            ScheduledExecutorService scheduler,
            ExecutorService scanExecutor,
            Duration scanInterval,
            Duration uploadInterval,
            Duration retention,
            Duration shutdownTimeout) {
        this.agentId = agentId;
        this.scanCoordinator = scanCoordinator;
        this.uploadCoordinator = uploadCoordinator;
        this.outbox = outbox;
        this.scheduler = scheduler;
        this.scanExecutor = scanExecutor;
        this.scanInterval = scanInterval;
        this.uploadInterval = uploadInterval;
        this.retention = retention;
        this.shutdownTimeout = shutdownTimeout;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
                isolated("scan", scanCoordinator::runCycle),
                0,
                scanInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(
                isolated("upload", uploadCoordinator::runCycle),
                0,
                uploadInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(
                isolated("cleanup", this::cleanup),
                0,
                Duration.ofHours(24).toMillis(),
                TimeUnit.MILLISECONDS);
        LOGGER.info("event=agent_started agentId={} result=scheduled", agentId);
    }

    public void runOnce() {
        scanCoordinator.runCycle();
        uploadCoordinator.runCycle();
        cleanup();
    }

    private void cleanup() {
        int deleted = outbox.cleanupUploadedBefore(Instant.now().minus(retention));
        LOGGER.info("event=cleanup_completed agentId={} deleted={} result=completed", agentId, deleted);
    }

    private Runnable isolated(String cycle, Runnable action) {
        return () -> {
            try {
                action.run();
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "event=cycle_failed agentId={} cycle={} result=isolated errorType={}",
                        agentId, cycle, exception.getClass().getSimpleName());
            }
        };
    }

    @Override
    public void close() {
        scheduler.shutdown();
        scanExecutor.shutdown();
        long timeoutMillis = shutdownTimeout.toMillis();
        try {
            if (!scheduler.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
            if (!scanExecutor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                scanExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
            scanExecutor.shutdownNow();
        }
        LOGGER.info("event=agent_stopped agentId={} result=graceful", agentId);
    }
}
