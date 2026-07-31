package com.vulnflow.agent.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.vulnflow.agent.outbox.FileAgentOutbox;
import com.vulnflow.agent.scanner.ScanArtifact;
import com.vulnflow.agent.scanner.ScanException;
import com.vulnflow.agent.scanner.VulnerabilityScanner;
import com.vulnflow.agent.shared.AgentObjectMapper;
import com.vulnflow.agent.target.ConfiguredTargetRegistry;
import com.vulnflow.agent.target.ScanTarget;
import com.vulnflow.agent.target.TargetType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanCoordinatorTest {

    @TempDir Path temporaryDirectory;

    @Test
    void concurrentCyclesNeverScanTheSameTargetTwice() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        VulnerabilityScanner scanner = new TestScanner(target -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            entered.countDown();
            try {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ScanException("synthetic interruption", exception);
                }
                return artifact();
            } finally {
                active.decrementAndGet();
            }
        });
        var executor = Executors.newFixedThreadPool(2);
        var cycleExecutor = Executors.newFixedThreadPool(2);
        try {
            ScanCoordinator coordinator = coordinator(scanner, List.of(target("one", "alpine:3.15")), executor);
            var first = cycleExecutor.submit(coordinator::runCycle);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var second = cycleExecutor.submit(coordinator::runCycle);
            second.get(2, TimeUnit.SECONDS);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);

            assertThat(maximum).hasValue(1);
            assertThat(outbox().list()).hasSize(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            cycleExecutor.shutdownNow();
        }
    }

    @Test
    void failureOfOneTargetDoesNotBlockOtherTargets() {
        VulnerabilityScanner scanner = new TestScanner(target -> {
            if (target.name().equals("broken")) {
                throw new ScanException("synthetic failure");
            }
            return artifact();
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            ScanCoordinator coordinator = coordinator(
                    scanner,
                    List.of(target("broken", "broken:1"), target("healthy", "healthy:1")),
                    executor);

            coordinator.runCycle();

            assertThat(outbox().list()).singleElement().satisfies(item ->
                    assertThat(item.target().name()).isEqualTo("healthy"));
        } finally {
            executor.shutdownNow();
        }
    }

    private ScanCoordinator coordinator(
            VulnerabilityScanner scanner,
            List<ScanTarget> targets,
            java.util.concurrent.ExecutorService executor) {
        var mapper = AgentObjectMapper.create();
        Path data = temporaryDirectory.resolve("data");
        return new ScanCoordinator(
                "agent-test",
                new ConfiguredTargetRegistry(targets),
                scanner,
                new FileAgentOutbox(data, 100_000, 20, mapper),
                new AgentStateStore(data, mapper),
                executor);
    }

    private FileAgentOutbox outbox() {
        return new FileAgentOutbox(
                temporaryDirectory.resolve("data"),
                100_000,
                20,
                AgentObjectMapper.create());
    }

    private ScanArtifact artifact() {
        try {
            Path report = Files.createTempFile(temporaryDirectory, "scan-", ".json");
            Files.writeString(report, "{}");
            return new ScanArtifact(report, Instant.now(), 2);
        } catch (java.io.IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    private ScanTarget target(String name, String reference) {
        return new ScanTarget(name, TargetType.CONTAINER_IMAGE, reference);
    }

    private static final class TestScanner implements VulnerabilityScanner {
        private final java.util.function.Function<ScanTarget, ScanArtifact> function;

        private TestScanner(java.util.function.Function<ScanTarget, ScanArtifact> function) {
            this.function = function;
        }

        @Override
        public String verifyAvailable() {
            return "fake";
        }

        @Override
        public ScanArtifact scan(ScanTarget target) {
            return function.apply(target);
        }
    }
}
