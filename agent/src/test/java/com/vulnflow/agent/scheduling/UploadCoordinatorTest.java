package com.vulnflow.agent.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.vulnflow.agent.client.AssetResolution;
import com.vulnflow.agent.client.ClientFailureKind;
import com.vulnflow.agent.client.UploadReceipt;
import com.vulnflow.agent.client.VulnFlowClient;
import com.vulnflow.agent.client.VulnFlowClientException;
import com.vulnflow.agent.outbox.FileAgentOutbox;
import com.vulnflow.agent.outbox.OutboxItem;
import com.vulnflow.agent.outbox.OutboxStatus;
import com.vulnflow.agent.shared.AgentObjectMapper;
import com.vulnflow.agent.target.ScanTarget;
import com.vulnflow.agent.target.TargetType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadCoordinatorTest {

    @TempDir Path temporaryDirectory;

    @Test
    void assetNotFoundIsResolvedOnceAndThenUploaded() throws Exception {
        Fixture fixture = fixture(new ReResolvingClient());

        fixture.coordinator().runCycle();

        OutboxItem item = fixture.outbox().list().get(0);
        assertThat(item.status()).isEqualTo(OutboxStatus.UPLOADED);
        assertThat(item.backendOutcome()).isEqualTo("ACCEPTED");
        assertThat(((ReResolvingClient) fixture.client()).resolveCalls).hasValue(2);
        assertThat(((ReResolvingClient) fixture.client()).uploadCalls).hasValue(2);
    }

    @Test
    void serverFailureRetriesWithoutDeletingTheReport() throws Exception {
        Fixture fixture = fixture(new FailingClient(ClientFailureKind.RETRYABLE));
        Instant before = Instant.now();

        fixture.coordinator().runCycle();

        OutboxItem item = fixture.outbox().list().get(0);
        assertThat(item.status()).isEqualTo(OutboxStatus.RETRY_WAIT);
        assertThat(item.nextAttemptAt()).isAfter(before);
        assertThat(Files.exists(fixture.outbox().reportPath(item))).isTrue();
    }

    @Test
    void alteredOutboxReportDeadLettersWithoutCallingVulnFlow() throws Exception {
        ReResolvingClient client = new ReResolvingClient();
        Fixture fixture = fixture(client);
        OutboxItem item = fixture.outbox().list().get(0);
        Files.writeString(fixture.outbox().reportPath(item), "{\"altered\":true}");

        fixture.coordinator().runCycle();

        assertThat(fixture.outbox().list()).singleElement().satisfies(failed -> {
            assertThat(failed.status()).isEqualTo(OutboxStatus.DEAD_LETTER);
            assertThat(failed.lastError()).isEqualTo("Stored outbox report failed integrity verification");
        });
        assertThat(client.resolveCalls).hasValue(0);
        assertThat(client.uploadCalls).hasValue(0);
    }

    @Test
    void authenticationUsesSlowRetryAndFunctionalClientErrorDeadLetters() throws Exception {
        Fixture authentication = fixture(new FailingClient(ClientFailureKind.CONFIGURATION));
        Instant before = Instant.now();
        authentication.coordinator().runCycle();
        OutboxItem retry = authentication.outbox().list().get(0);
        assertThat(retry.status()).isEqualTo(OutboxStatus.RETRY_WAIT);
        assertThat(retry.nextAttemptAt()).isAfterOrEqualTo(before.plus(Duration.ofHours(1)));

        Path secondData = temporaryDirectory.resolve("second");
        Fixture permanent = fixture(new FailingClient(ClientFailureKind.PERMANENT), secondData);
        permanent.coordinator().runCycle();
        assertThat(permanent.outbox().list()).singleElement()
                .extracting(OutboxItem::status)
                .isEqualTo(OutboxStatus.DEAD_LETTER);
    }

    @Test
    void concurrentUploadCyclesSendTheClaimedItemOnlyOnce() throws Exception {
        BlockingClient client = new BlockingClient();
        Fixture fixture = fixture(client);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(fixture.coordinator()::runCycle);
            assertThat(client.uploadStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var second = executor.submit(fixture.coordinator()::runCycle);
            second.get(5, TimeUnit.SECONDS);
            client.releaseUpload.countDown();
            first.get(5, TimeUnit.SECONDS);

            assertThat(client.uploadCalls).hasValue(1);
            assertThat(fixture.outbox().list()).singleElement()
                    .extracting(OutboxItem::status)
                    .isEqualTo(OutboxStatus.UPLOADED);
        } finally {
            client.releaseUpload.countDown();
            executor.shutdownNow();
        }
    }

    private Fixture fixture(VulnFlowClient client) throws Exception {
        return fixture(client, temporaryDirectory.resolve("data"));
    }

    private Fixture fixture(VulnFlowClient client, Path data) throws Exception {
        var mapper = AgentObjectMapper.create();
        FileAgentOutbox outbox = new FileAgentOutbox(data, 10_000, 10, mapper);
        Path report = Files.createTempFile(temporaryDirectory, "report-", ".json");
        Files.writeString(report, "{}");
        outbox.enqueue("agent-test", target(), Instant.now(), report);
        AgentStateStore stateStore = new AgentStateStore(data, mapper);
        UploadCoordinator coordinator = new UploadCoordinator(
                "agent-test",
                outbox,
                new AssetCache(data, mapper),
                client,
                stateStore,
                Duration.ofSeconds(5));
        return new Fixture(outbox, client, coordinator);
    }

    private ScanTarget target() {
        return new ScanTarget("alpine", TargetType.CONTAINER_IMAGE, "alpine:3.15");
    }

    private record Fixture(FileAgentOutbox outbox, VulnFlowClient client, UploadCoordinator coordinator) {
    }

    private static final class ReResolvingClient implements VulnFlowClient {
        private final AtomicInteger resolveCalls = new AtomicInteger();
        private final AtomicInteger uploadCalls = new AtomicInteger();

        @Override
        public AssetResolution resolveAsset(ScanTarget target) {
            int call = resolveCalls.incrementAndGet();
            return new AssetResolution(new UUID(0, call), true);
        }

        @Override
        public UploadReceipt uploadTrivyReport(UUID assetId, Path report) {
            if (uploadCalls.incrementAndGet() == 1) {
                throw new VulnFlowClientException(ClientFailureKind.ASSET_NOT_FOUND, "missing asset");
            }
            return new UploadReceipt(UUID.randomUUID(), UUID.randomUUID(), "ACCEPTED");
        }
    }

    private static final class FailingClient implements VulnFlowClient {
        private final ClientFailureKind kind;

        private FailingClient(ClientFailureKind kind) {
            this.kind = kind;
        }

        @Override
        public AssetResolution resolveAsset(ScanTarget target) {
            if (kind != ClientFailureKind.ASSET_NOT_FOUND) {
                throw new VulnFlowClientException(kind, "safe client failure");
            }
            return new AssetResolution(UUID.randomUUID(), true);
        }

        @Override
        public UploadReceipt uploadTrivyReport(UUID assetId, Path report) {
            throw new VulnFlowClientException(kind, "safe client failure");
        }
    }

    private static final class BlockingClient implements VulnFlowClient {
        private final AtomicInteger uploadCalls = new AtomicInteger();
        private final CountDownLatch uploadStarted = new CountDownLatch(1);
        private final CountDownLatch releaseUpload = new CountDownLatch(1);

        @Override
        public AssetResolution resolveAsset(ScanTarget target) {
            return new AssetResolution(UUID.randomUUID(), true);
        }

        @Override
        public UploadReceipt uploadTrivyReport(UUID assetId, Path report) {
            uploadCalls.incrementAndGet();
            uploadStarted.countDown();
            try {
                if (!releaseUpload.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release the test upload");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Test upload was interrupted", exception);
            }
            return new UploadReceipt(UUID.randomUUID(), UUID.randomUUID(), "ACCEPTED");
        }
    }
}
