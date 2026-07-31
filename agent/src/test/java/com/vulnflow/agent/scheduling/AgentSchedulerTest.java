package com.vulnflow.agent.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vulnflow.agent.client.AssetResolution;
import com.vulnflow.agent.client.UploadReceipt;
import com.vulnflow.agent.client.VulnFlowClient;
import com.vulnflow.agent.outbox.FileAgentOutbox;
import com.vulnflow.agent.scanner.ScanArtifact;
import com.vulnflow.agent.scanner.VulnerabilityScanner;
import com.vulnflow.agent.shared.AgentObjectMapper;
import com.vulnflow.agent.target.ConfiguredTargetRegistry;
import com.vulnflow.agent.target.ScanTarget;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSchedulerTest {

    @TempDir Path temporaryDirectory;

    @Test
    void gracefulCloseWaitsForActiveWorkWithinTheConfiguredLimit() throws Exception {
        var scanExecutor = Executors.newSingleThreadExecutor();
        var scheduledExecutor = Executors.newScheduledThreadPool(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        scanExecutor.submit(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        AgentScheduler scheduler = scheduler(scanExecutor, scheduledExecutor, Duration.ofSeconds(2));
        var closer = Executors.newSingleThreadExecutor();
        try {
            var closing = closer.submit(scheduler::close);
            assertThatThrownBy(() -> closing.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            closing.get(2, TimeUnit.SECONDS);
            assertThat(scanExecutor.isTerminated()).isTrue();
        } finally {
            release.countDown();
            closer.shutdownNow();
            scheduledExecutor.shutdownNow();
            scanExecutor.shutdownNow();
        }
    }

    private AgentScheduler scheduler(
            java.util.concurrent.ExecutorService scanExecutor,
            java.util.concurrent.ScheduledExecutorService scheduledExecutor,
            Duration shutdownTimeout) {
        var mapper = AgentObjectMapper.create();
        Path data = temporaryDirectory.resolve("data");
        FileAgentOutbox outbox = new FileAgentOutbox(data, 10_000, 10, mapper);
        AgentStateStore stateStore = new AgentStateStore(data, mapper);
        ScanCoordinator scans = new ScanCoordinator(
                "agent-test",
                new ConfiguredTargetRegistry(List.of()),
                new NoOpScanner(),
                outbox,
                stateStore,
                scanExecutor);
        UploadCoordinator uploads = new UploadCoordinator(
                "agent-test",
                outbox,
                new AssetCache(data, mapper),
                new NoOpClient(),
                stateStore,
                Duration.ofSeconds(1));
        return new AgentScheduler(
                "agent-test",
                scans,
                uploads,
                outbox,
                scheduledExecutor,
                scanExecutor,
                Duration.ofHours(1),
                Duration.ofHours(1),
                Duration.ofDays(1),
                shutdownTimeout);
    }

    private static final class NoOpScanner implements VulnerabilityScanner {
        @Override public String verifyAvailable() { return "fake"; }
        @Override public ScanArtifact scan(ScanTarget target) { throw new UnsupportedOperationException(); }
    }

    private static final class NoOpClient implements VulnFlowClient {
        @Override public AssetResolution resolveAsset(ScanTarget target) { throw new UnsupportedOperationException(); }
        @Override public UploadReceipt uploadTrivyReport(UUID assetId, Path report) {
            throw new UnsupportedOperationException();
        }
    }
}
