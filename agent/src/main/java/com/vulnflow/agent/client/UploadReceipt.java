package com.vulnflow.agent.client;

import java.util.UUID;

public record UploadReceipt(UUID scanId, UUID jobId, String outcome) {
}
