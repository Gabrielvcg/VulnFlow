package com.vulnflow.agent.scheduling;

import com.vulnflow.agent.client.AssetResolution;
import com.vulnflow.agent.client.ClientFailureKind;
import com.vulnflow.agent.client.UploadReceipt;
import com.vulnflow.agent.client.VulnFlowClient;
import com.vulnflow.agent.client.VulnFlowClientException;
import com.vulnflow.agent.outbox.AgentOutbox;
import com.vulnflow.agent.outbox.OutboxItem;
import com.vulnflow.agent.outbox.OutboxIntegrityException;
import com.vulnflow.agent.outbox.OutboxIntegrityVerifier;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UploadCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(UploadCoordinator.class);
    private static final int UPLOAD_BATCH_SIZE = 20;
    private final String agentId;
    private final AgentOutbox outbox;
    private final AssetCache assetCache;
    private final VulnFlowClient client;
    private final AgentStateStore stateStore;
    private final Duration baseRetry;
    private final OutboxIntegrityVerifier integrityVerifier;
    private final AtomicBoolean cycleRunning = new AtomicBoolean();

    public UploadCoordinator(
            String agentId,
            AgentOutbox outbox,
            AssetCache assetCache,
            VulnFlowClient client,
            AgentStateStore stateStore,
            Duration baseRetry) {
        this.agentId = agentId;
        this.outbox = outbox;
        this.assetCache = assetCache;
        this.client = client;
        this.stateStore = stateStore;
        this.baseRetry = baseRetry;
        this.integrityVerifier = new OutboxIntegrityVerifier();
    }

    public void runCycle() {
        if (!cycleRunning.compareAndSet(false, true)) {
            LOGGER.info("event=upload_cycle_skipped agentId={} result=already_running", agentId);
            return;
        }
        try {
            Instant now = Instant.now();
            for (OutboxItem item : outbox.claimReady(now, UPLOAD_BATCH_SIZE)) {
                upload(item);
            }
        } finally {
            cycleRunning.set(false);
        }
    }

    private void upload(OutboxItem claimed) {
        Instant now = Instant.now();
        try {
            integrityVerifier.verify(claimed, outbox.reportPath(claimed));
            OutboxItem item = ensureAsset(claimed, now, false);
            UploadReceipt receipt;
            try {
                receipt = client.uploadTrivyReport(item.assetId(), outbox.reportPath(item), item.scanRequestId(), item.claimToken());
            } catch (VulnFlowClientException exception) {
                if (exception.kind() != ClientFailureKind.ASSET_NOT_FOUND) {
                    throw exception;
                }
                assetCache.invalidate(item.target());
                item = ensureAsset(item.withAsset(null, now), now, true);
                receipt = client.uploadTrivyReport(item.assetId(), outbox.reportPath(item), item.scanRequestId(), item.claimToken());
            }
            outbox.markUploaded(item.id(), receipt, now);
            stateStore.recordSuccessfulUpload(now);
            LOGGER.info(
                    "event=upload_completed agentId={} target={} outboxItemId={} assetId={} attempt={} result={}",
                    agentId, item.target().name(), item.id(), item.assetId(), item.uploadAttempts(), receipt.outcome());
        } catch (VulnFlowClientException exception) {
            handleClientFailure(claimed, exception, now);
        } catch (OutboxIntegrityException exception) {
            outbox.markDeadLetter(claimed.id(), exception.getMessage(), now);
            LOGGER.error(
                    "event=upload_failed agentId={} target={} outboxItemId={} attempt={} result=dead_letter errorType=integrity",
                    agentId, claimed.target().name(), claimed.id(), claimed.uploadAttempts());
        } catch (RuntimeException exception) {
            outbox.markDeadLetter(claimed.id(), "Unexpected deterministic agent failure", now);
            LOGGER.error(
                    "event=upload_failed agentId={} target={} outboxItemId={} attempt={} result=dead_letter errorType={}",
                    agentId,
                    claimed.target().name(),
                    claimed.id(),
                    claimed.uploadAttempts(),
                    exception.getClass().getSimpleName());
        }
    }

    private OutboxItem ensureAsset(OutboxItem item, Instant now, boolean forceResolve) {
        UUID assetId = forceResolve ? null : item.assetId();
        if (assetId == null && !forceResolve) {
            assetId = assetCache.find(item.target()).orElse(null);
        }
        if (assetId == null) {
            AssetResolution resolution = client.resolveAsset(item.target());
            assetId = resolution.assetId();
            assetCache.put(item.target(), assetId);
        }
        outbox.assignAsset(item.id(), assetId, now);
        return item.withAsset(assetId, now);
    }

    private void handleClientFailure(OutboxItem item, VulnFlowClientException exception, Instant now) {
        if (exception.kind() == ClientFailureKind.PERMANENT) {
            outbox.markDeadLetter(item.id(), exception.safeError(), now);
            logFailure(item, "dead_letter", exception);
            return;
        }
        Duration delay = exception.kind() == ClientFailureKind.CONFIGURATION
                ? max(backoff(item.uploadAttempts()), Duration.ofHours(1))
                : backoff(item.uploadAttempts());
        outbox.markRetry(item.id(), now.plus(delay), exception.safeError(), now);
        logFailure(item, "retry_wait", exception);
    }

    private Duration backoff(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 6);
        return baseRetry.multipliedBy(1L << exponent);
    }

    private Duration max(Duration first, Duration second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    private void logFailure(OutboxItem item, String result, VulnFlowClientException exception) {
        LOGGER.warn(
                "event=upload_failed agentId={} target={} outboxItemId={} assetId={} attempt={} result={} errorKind={}",
                agentId,
                item.target().name(),
                item.id(),
                item.assetId(),
                item.uploadAttempts(),
                result,
                exception.kind());
    }
}
