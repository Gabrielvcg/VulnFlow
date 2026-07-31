package com.vulnflow.agent.client;

import com.vulnflow.agent.target.ScanTarget;
import java.nio.file.Path;
import java.util.UUID;

public interface VulnFlowClient {

    AssetResolution resolveAsset(ScanTarget target);

    UploadReceipt uploadTrivyReport(UUID assetId, Path report);
}
