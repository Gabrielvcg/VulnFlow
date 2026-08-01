package com.vulnflow.aws.ingestion;

import com.vulnflow.ingestion.IngestionSubmission;

record AwsRegistrationResult(IngestionSubmission submission, boolean ownsUploadedPayload) {
}
