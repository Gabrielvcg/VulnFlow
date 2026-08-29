package com.vulnflow.agent.client;

import com.vulnflow.agent.target.ScanTarget;
import java.nio.file.Path;
import java.util.UUID;

public interface VulnFlowClient {

    AssetResolution resolveAsset(ScanTarget target);

    UploadReceipt uploadTrivyReport(UUID assetId, Path report);

    default UploadReceipt uploadTrivyReport(UUID assetId, Path report, UUID scanRequestId, UUID claimToken) {
        return uploadTrivyReport(assetId, report);
    }

    default AgentClaim claimScan(String agentId, AgentHeartbeat heartbeat) { return null; }
    default void heartbeat(String agentId, AgentHeartbeat heartbeat) {}
    default void startScan(String agentId, UUID requestId, UUID claimToken) {}
    default void failScan(String agentId, UUID requestId, UUID claimToken, String safeError) {}
}
