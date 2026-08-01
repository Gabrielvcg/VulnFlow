# ADR-016: AWS infrastructure adapters

Status: Accepted for 0.4.0 preparation; disabled by default.

## Decision

Keep `ReportStorage`, `ProcessingResultStore`, and `IngestionMessagePublisher` in `processing-core`. Implement S3 and SQS with AWS SDK for Java 2.x in `aws-adapters`. Spring creates AWS clients only under the explicit `aws` profile; the default profile keeps `LocalFileReportStorage` and creates no AWS client.

Do not add `IngestionMessageConsumer`, `DeadLetterPublisher`, or `ScanStateStore`: the Lambda runtime is the consumer, SQS redrive owns DLQ routing, and scan completion belongs atomically with result persistence.

## Consequences

Unit tests mock SDK clients and make no AWS requests. Client timeouts, bucket, prefix, queue URL, region, and payload size are configuration. Activating the profile does not itself select a complete AWS ingestion route; it is an integration seam for a later slice.
