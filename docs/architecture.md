# Architecture

## Scope

VulnFlow 0.2.0 is a Spring Boot modular monolith with PostgreSQL and a local
persistent report volume. The API and worker run in the same process, but their
transactional boundaries are independent so either side can later move behind
an adapter.

## Components

```text
ScanIngestionController
  -> DefaultScanIngestionService
      -> AssetService
      -> ScanRegistrationService
          -> ScanRepository
          -> ReportStorage -> LocalFileReportStorage
          -> IngestionJobRepository

LocalIngestionWorker (@Scheduled)
  -> IngestionJobRecoveryService
  -> JobClaimService -> PostgreSQL FOR UPDATE SKIP LOCKED
  -> IngestionJobProcessor
      -> ReportStorage.load
      -> PayloadIntegrityVerifier
      -> TrivyVulnerabilityReportParser
      -> IngestionPersistenceService
          -> FindingRiskCalculator
          -> FindingRepository
          -> ScanRepository
          -> IngestionJobRepository
      -> JobFailureService
      -> JobFailureClassifier

IngestionJobController
  -> IngestionJobQueryService
  -> IngestionJobRedriveService
```

## HTTP-to-worker flow

1. Spring Security validates `X-API-Key`.
2. The controller validates multipart transport, asset, media type, and size.
3. The service reads at most the configured upload limit and computes SHA-256.
4. Registration inserts a `RECEIVED` scan with `ON CONFLICT DO NOTHING` and
   locks the matching `(asset_id, content_hash)` row.
5. For new content, an internal storage key is generated, the report is written
   through a temporary file, and one `PENDING` job is inserted.
6. Registration commits and HTTP returns `202`; no JSON parsing occurs in the
   request thread.
7. The scheduler selects available jobs with `FOR UPDATE SKIP LOCKED LIMIT n`.
8. A short claim transaction sets the job and scan to `PROCESSING`, increments
   attempts, stores `lockedAt`, generates a non-reusable claim-token UUID, and
   returns a detached `JobClaim`.
9. After commit, the worker loads the report, verifies its SHA-256, parses it,
   and calculates findings.
10. A completion transaction locks job then scan, verifies the claim token,
    replaces findings, and marks both records `COMPLETED` atomically.
11. Functional errors dead-letter immediately. Transient errors schedule a
    future retry or dead-letter after the maximum attempt.

## State ownership

`Scan` is the domain result:

- `RECEIVED`: durable payload/job waiting, retry waiting, or redriven.
- `PROCESSING`: the current job attempt owns a lease.
- `COMPLETED`: findings and scanner version committed.
- `FAILED`: the job is `DEAD_LETTER`.

`IngestionJob` is the work source of truth:

- `PENDING`, `PROCESSING`, `RETRY_WAIT`, `COMPLETED`, `DEAD_LETTER`.

There is no scan-only recovery path in 0.2.0. Recovery reads and transitions
jobs, then updates their scans in the same transaction.

## Transaction boundaries

| Boundary | Operations | External work under DB lock |
| --- | --- | --- |
| Registration | scan deduplication, payload write, job insert | Bounded local write, no parsing |
| Claim | job lock, attempt increment, scan `PROCESSING` | None |
| Processing | storage read, JSON parse, risk calculation | No DB transaction |
| Completion | finding replacement, scan and job completion | None |
| Failure | retry/dead-letter and matching scan transition | None |
| Recovery | stale job retry/dead-letter and scan transition | None |
| Redrive | state check, payload existence, reset job and scan | Bounded existence check |

The registration transaction registers payload cleanup on rollback because a
filesystem and PostgreSQL do not provide a distributed transaction.

## Concurrency and idempotency

- `(asset_id, content_hash)` serializes duplicate report submission.
- `UNIQUE(scan_id)` prevents a second job for a scan.
- `SKIP LOCKED` lets backend instances claim different jobs without waiting.
- Claim transactions finish before parsing.
- `attemptCount` counts retry budget and selects backoff; it is not fencing.
- `claimToken` is a new UUID for every claim. Completion and failure require the
  current token, which prevents ABA after recovery and manual redrive.
- Completion deletes and recreates a scan's findings within one transaction,
  so redelivery cannot leave duplicates or partial findings.
- Concurrent redrive locks the job row; only the first transition succeeds.

## Recovery and backoff

Stale `PROCESSING` jobs are selected by `lockedAt`. If attempts remain they move
to `RETRY_WAIT`, otherwise to `DEAD_LETTER`. Recovery uses row locks and is
idempotent because the first execution changes the status. Both paths clear the
claim token before releasing the transaction.

The default retry sequence is 5 seconds, 30 seconds, and 2 minutes. Retries set
`availableAt`; no worker sleeps while waiting.

## Future adapter boundaries

```text
LocalFileReportStorage -> S3ReportStorage
IngestionJobRepository/claim service -> SQS delivery adapter
LocalIngestionWorker -> Lambda handler
```

These are migration seams only. No AWS SDK or cloud resource is used in 0.2.0.
SQS will use receipt handles and visibility timeouts rather than copying the
current PostgreSQL row-locking protocol. S3 calls must not be introduced under
long-held database locks.
