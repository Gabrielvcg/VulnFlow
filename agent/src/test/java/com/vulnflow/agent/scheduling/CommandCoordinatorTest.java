package com.vulnflow.agent.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.vulnflow.agent.client.*;
import com.vulnflow.agent.outbox.*;
import com.vulnflow.agent.shared.AgentObjectMapper;
import com.vulnflow.agent.target.*;
import com.vulnflow.agent.scanner.VulnerabilityScanner;
import com.vulnflow.agent.scanner.ScanArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandCoordinatorTest {
    @TempDir Path directory;

    @Test
    void notifiedDeadLetterDoesNotBlockClaimsIncludingAfterRestart() throws Exception {
        FileAgentOutbox outbox = outbox();
        OutboxItem item = deadLetter(outbox);
        StubClient client = new StubClient();
        cycle(outbox, client);
        cycle(outbox(), client);
        assertThat(client.claims).isEqualTo(2);
        assertThat(client.failures).isEqualTo(1);
        assertThat(outbox.reportPath(item)).exists();
        assertThat(outbox.list().get(0).status()).isEqualTo(OutboxStatus.DEAD_LETTER);
    }

    @Test
    void failedNotificationIsRetriedWithoutBlockingNewClaims() throws Exception {
        FileAgentOutbox outbox = outbox();
        OutboxItem item = deadLetter(outbox);
        StubClient client = new StubClient();
        client.unavailable = true;
        cycle(outbox, client);
        assertThat(outbox.isFailureReported(item.id())).isFalse();
        client.unavailable = false;
        cycle(outbox(), client);
        assertThat(client.claims).isEqualTo(2);
        assertThat(client.failures).isEqualTo(2);
        assertThat(outbox.isFailureReported(item.id())).isTrue();
    }

    private void cycle(FileAgentOutbox outbox, StubClient client) {
        var executor = Executors.newSingleThreadExecutor();
        try {
            new CommandCoordinator("agent-a", true, directory, client,
                    new VulnerabilityScanner() {
                        public String verifyAvailable() { return "test"; }
                        public ScanArtifact scan(ScanTarget target) {
                            throw new AssertionError("No scan should be started without a claim");
                        }
                    },
                    outbox, executor, List::of).runCycle();
        } finally {
            executor.shutdownNow();
        }
    }

    private FileAgentOutbox outbox() {
        return new FileAgentOutbox(directory, 10_000, 10, AgentObjectMapper.create());
    }

    private OutboxItem deadLetter(FileAgentOutbox outbox) throws Exception {
        Path report = directory.resolve("report.json");
        Files.writeString(report, "{}");
        OutboxItem item = outbox.enqueue("agent-a", new ScanTarget("test", TargetType.CONTAINER_IMAGE, "alpine:3.20"),
                Instant.now(), report, UUID.randomUUID(), UUID.randomUUID());
        outbox.markDeadLetter(item.id(), "Rejected", Instant.now());
        return item;
    }

    private static class StubClient implements VulnFlowClient {
        int claims;
        int failures;
        boolean unavailable;
        public AssetResolution resolveAsset(ScanTarget target) { throw new AssertionError("Unexpected resolution"); }
        public UploadReceipt uploadTrivyReport(UUID assetId, Path report) { throw new AssertionError("Unexpected upload"); }
        public AgentClaim claimScan(String agentId, AgentHeartbeat heartbeat) { claims++; return null; }
        public void failScan(String agentId, UUID requestId, UUID token, String error) {
            failures++;
            if (unavailable) throw new VulnFlowClientException(ClientFailureKind.RETRYABLE, "Unavailable");
        }
    }
}
