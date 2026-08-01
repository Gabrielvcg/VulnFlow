package com.vulnflow.aws.ingestion;

import java.util.UUID;

record AwsPublicationClaim(
        UUID eventId,
        String payloadKey,
        String eventJson,
        UUID claimToken,
        int attempt) {
}
