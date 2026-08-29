package com.vulnflow.agent.outbox;

import com.vulnflow.agent.client.UploadReceipt;
import com.vulnflow.agent.target.ScanTarget;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AgentOutbox {

    OutboxItem enqueue(String agentId, ScanTarget target, Instant scannedAt, Path report);

    default OutboxItem enqueue(String agentId, ScanTarget target, Instant scannedAt, Path report,
                               UUID scanRequestId, UUID claimToken) {
        return enqueue(agentId, target, scannedAt, report);
    }

    List<OutboxItem> claimReady(Instant now, int limit);

    void assignAsset(UUID itemId, UUID assetId, Instant now);

    void markRetry(UUID itemId, Instant nextAttemptAt, String safeError, Instant now);

    void markDeadLetter(UUID itemId, String safeError, Instant now);

    void markUploaded(UUID itemId, UploadReceipt receipt, Instant now);

    Path reportPath(OutboxItem item);

    List<OutboxItem> list();

    OutboxStats stats();

    int recoverInterrupted(Instant now);

    int cleanupUploadedBefore(Instant cutoff);
}
