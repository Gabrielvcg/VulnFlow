package com.vulnflow.ingestion;

import java.util.UUID;

public record LocalCompletionContext(UUID jobId, UUID expectedClaimToken) {
}
