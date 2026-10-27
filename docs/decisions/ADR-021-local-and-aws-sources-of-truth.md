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

## Asset history projection

The UI asset scan history keeps its existing `ScanResponse` and page metadata. It reads summaries in
one `findScans` call for only the scans returned in that page, with no per-row remote lookup. When a
summary exists, its processing state, scanner version and completion timestamp are authoritative.
The existing ScanStatus enum represents RECEIVED/PUBLISH_PENDING/QUEUED summaries as RECEIVED;
PROCESSING, COMPLETED and FAILED retain their corresponding states. No response enum is expanded.

Receipt time, startedAt, asset/scan identity, scanner type, source filename and content hash remain
local metadata. FAILED responses use the summary's safeError as failureReason; other remote states
clear stale local failure text. No raw exception is introduced into the response. Absent summaries
and local-only mode preserve the original local projection. Empty pages avoid remote reads and a
reader failure propagates rather than silently substituting local success. The projection never
updates PostgreSQL scan state. This changes presentation consistency, not result ownership or data.
