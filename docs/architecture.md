# Architecture

## Current system

VulnFlow 0.1.1 is one Spring Boot deployable organized by feature:

```text
com.vulnflow
|-- asset
|-- scan
|-- finding
|-- ingestion
|-- dashboard
|-- security
|-- shared
`-- config
```

Controllers validate transport input and delegate. Services own business and
transaction behavior. Repositories own persistence. API responses are DTOs, not
JPA entities.

## Ingestion sequence

1. Authenticate `X-API-Key`.
2. Resolve the asset or return `404`.
3. Reject empty, oversized, or non-JSON uploads.
4. Read bounded bytes and compute SHA-256.
5. `ScanRegistrationService.registerProcessing()` opens `REQUIRES_NEW`.
6. PostgreSQL `ON CONFLICT` preserves `(asset_id, content_hash)` uniqueness.
7. A pessimistic row lock decides one of:
   - new claim: `IMPORTED`;
   - failed claim: reset and `RETRIED`;
   - completed row: `DUPLICATE`;
   - active row: `ALREADY_PROCESSING`.
8. Parse the validated Trivy structure outside a database transaction.
9. `IngestionPersistenceService.complete()` opens `REQUIRES_NEW`, saves all
   findings, and marks the scan `COMPLETED` atomically.
10. A processing error calls `ScanFailureService.markFailed()` in another
    `REQUIRES_NEW` transaction and then rethrows the original exception.
11. Response counts are executed after completion and outside failure marking.

There is no self-invocation between the three transactional operations; each is
called through a separate Spring bean proxy.

## Scan state model

```text
RECEIVED
   |
   v
PROCESSING -------> COMPLETED
   |
   v
FAILED
   |
   `--------------> PROCESSING
```

New HTTP ingestions are inserted directly as `PROCESSING`. `RECEIVED` remains a
domain/schema state for possible future adapters. Failed retries reuse the same
row and identifier. Completed rows are immutable to duplicate ingestion.

`ScanRecoveryService` performs an atomic conditional update from stale
`PROCESSING` to `FAILED`. It is idempotent and callable later by a scheduler, but
0.1.1 does not schedule it automatically.

## Data model and migrations

- `Asset` identifies a host, image, or application.
- `Scan` records the report hash, lifecycle, source metadata, and generic
  failure reason.
- `Finding` is a vulnerability snapshot tied to a scan and asset.

Flyway V1 creates the original model. V2 adds `scans.failure_reason` and a
partial processing-time index. V1 was not rewritten, so V2 works on both an
existing 0.1.0 database and an empty database.

## Security boundary

Spring Security applies a stateless API-key filter to `/api/v1/**`. Health and
local Swagger remain public. Compose limits network exposure to localhost.
This is machine-to-machine hardening, not the final human identity model.

## Deliberately deferred

Syft, asynchronous processing, reconciliation, frontend, AWS, S3, SQS, Lambda,
DynamoDB, EventBridge, SNS, and microservices remain outside this version.
Terraform still defines zero resources.
