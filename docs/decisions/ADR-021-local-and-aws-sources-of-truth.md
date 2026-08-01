# ADR-021: Separate local and AWS result sources of truth

Status: Accepted for 0.4.1.

## Decision

Local mode keeps PostgreSQL as the source of truth for `ingestion_jobs`, findings, and scan completion.
AWS mode keeps PostgreSQL only for asset identity, upload deduplication, scan acceptance, and publication
outbox state. DynamoDB is the source of truth for Lambda processing status and normalized results.

Under profile `aws`, local workers and PostgreSQL scan/finding/job controllers are not registered. The
AWS scan result controller reads DynamoDB through `ProcessingResultReader`, falling back to the short
PostgreSQL pending projection only before a DynamoDB result exists.

## Consequences

PostgreSQL `scans.status` can remain `RECEIVED` after an AWS result completes. It must not be used to
answer AWS result queries or dashboards. Existing local dashboards are intentionally unavailable under
the AWS profile until their DynamoDB access patterns are designed.
