# ADR-018: Lambda versus the local worker

Status: Accepted as two adapters over one processor.

## Decision

`LocalIngestionWorker` remains a Spring scheduled adapter that claims PostgreSQL jobs and applies local retry rules. `SqsVulnerabilityReportHandler` is a separate Java Lambda adapter invoked by an event source mapping and returns per-record failures.

Lambda must not poll SQS, sleep, calculate local backoff, claim SQL rows, or move messages to a DLQ. Both runtimes invoke `VulnerabilityReportProcessor` without copying parser or risk logic.

## Consequences

Runtime concerns stay visible and testable. A Lambda artifact is packageable now, but deployment remains safety-gated until exactly one production `LambdaProcessingResultStoreProvider` is implemented and reviewed.
