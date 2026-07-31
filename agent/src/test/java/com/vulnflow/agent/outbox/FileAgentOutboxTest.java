package com.vulnflow.agent.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vulnflow.agent.client.UploadReceipt;
import com.vulnflow.agent.shared.AgentObjectMapper;
import com.vulnflow.agent.target.ScanTarget;
import com.vulnflow.agent.target.TargetType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileAgentOutboxTest {

    @TempDir Path temporaryDirectory;

    @Test
    void persistsAtomicallyAndSurvivesRestart() throws Exception {
        Path report = report("{\"Results\":[]}");
        FileAgentOutbox first = outbox(10_000, 10);

        OutboxItem stored = first.enqueue("agent-a", target(), Instant.now(), report);
        FileAgentOutbox restarted = outbox(10_000, 10);

        assertThat(restarted.list()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(stored.id());
            assertThat(item.status()).isEqualTo(OutboxStatus.PENDING);
            assertThat(item.sha256()).hasSize(64);
            assertThat(Files.isRegularFile(restarted.reportPath(item))).isTrue();
        });
        try (var paths = Files.list(temporaryDirectory.resolve("data/outbox/items"))) {
            assertThat(paths.map(path -> path.getFileName().toString()))
                    .allMatch(name -> !name.endsWith(".tmp"));
        }
    }

    @Test
    void restartRemovesOnlyOrphanedInternalTemporaryDirectories() throws Exception {
        Path items = temporaryDirectory.resolve("data/outbox/items");
        Files.createDirectories(items);
        Path orphan = Files.createDirectory(items.resolve("." + UUID.randomUUID() + ".tmp"));
        Files.writeString(orphan.resolve("partial-report.json"), "partial");
        Path unrelated = Files.createDirectory(items.resolve("operator-notes"));

        outbox(10_000, 10);

        assertThat(orphan).doesNotExist();
        assertThat(unrelated).isDirectory();
    }

    @Test
    void recoversUploadingItemsAndSchedulesRetry() throws Exception {
        FileAgentOutbox first = outbox(10_000, 10);
        OutboxItem stored = first.enqueue("agent-a", target(), Instant.now(), report("{}"));
        assertThat(first.claimReady(Instant.now().plusSeconds(1), 1)).singleElement();

        FileAgentOutbox restarted = outbox(10_000, 10);
        assertThat(restarted.recoverInterrupted(Instant.now())).isOne();

        OutboxItem recovered = restarted.list().get(0);
        assertThat(recovered.id()).isEqualTo(stored.id());
        assertThat(recovered.status()).isEqualTo(OutboxStatus.RETRY_WAIT);
        assertThat(recovered.lastError()).doesNotContain("path");
    }

    @Test
    void twoClaimersCannotOwnTheSameItem() throws Exception {
        FileAgentOutbox outbox = outbox(10_000, 10);
        outbox.enqueue("agent-a", target(), Instant.now(), report("{}"));
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                return outbox.claimReady(Instant.now().plusSeconds(1), 1);
            });
            var second = executor.submit(() -> {
                start.await();
                return outbox.claimReady(Instant.now().plusSeconds(1), 1);
            });
            start.countDown();

            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .extracting(List::size)
                    .containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void enforcesCapacityWithoutDeletingPendingReports() throws Exception {
        FileAgentOutbox outbox = outbox(4, 1);
        outbox.enqueue("agent-a", target(), Instant.now(), report("1234"));

        assertThatThrownBy(() -> outbox.enqueue("agent-a", target(), Instant.now(), report("1")))
                .isInstanceOf(OutboxCapacityException.class);
        assertThat(outbox.list()).singleElement().extracting(OutboxItem::status)
                .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void retentionDeletesOnlyUploadedItems() throws Exception {
        FileAgentOutbox outbox = outbox(10_000, 10);
        OutboxItem uploaded = outbox.enqueue("agent-a", target(), Instant.now(), report("{}"));
        OutboxItem pending = outbox.enqueue("agent-a", otherTarget(), Instant.now(), report("{}"));
        OutboxItem dead = outbox.enqueue("agent-a", thirdTarget(), Instant.now(), report("{}"));
        Instant old = Instant.now().minusSeconds(10_000);
        outbox.claimReady(Instant.now().plusSeconds(1), 3);
        outbox.markUploaded(uploaded.id(), new UploadReceipt(UUID.randomUUID(), UUID.randomUUID(), "ACCEPTED"), old);
        outbox.markRetry(pending.id(), Instant.now(), "temporary", Instant.now());
        outbox.markDeadLetter(dead.id(), "permanent", Instant.now());

        assertThat(outbox.cleanupUploadedBefore(Instant.now().minusSeconds(100))).isOne();
        assertThat(outbox.list()).extracting(OutboxItem::status)
                .containsExactlyInAnyOrder(OutboxStatus.RETRY_WAIT, OutboxStatus.DEAD_LETTER);
    }

    private FileAgentOutbox outbox(long maxBytes, int maxItems) {
        return new FileAgentOutbox(
                temporaryDirectory.resolve("data"),
                maxBytes,
                maxItems,
                AgentObjectMapper.create());
    }

    private Path report(String content) throws Exception {
        Path file = Files.createTempFile(temporaryDirectory, "report-", ".json");
        Files.writeString(file, content);
        return file;
    }

    private ScanTarget target() {
        return new ScanTarget("alpine", TargetType.CONTAINER_IMAGE, "alpine:3.15");
    }

    private ScanTarget otherTarget() {
        return new ScanTarget("nginx", TargetType.CONTAINER_IMAGE, "nginx:latest");
    }

    private ScanTarget thirdTarget() {
        return new ScanTarget("debian", TargetType.CONTAINER_IMAGE, "debian:stable");
    }
}
