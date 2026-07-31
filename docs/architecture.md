# Architecture

## Scope

VulnFlow 0.3.1 consists of a Spring Boot modular-monolith backend and an
independent Java 17 scanning agent. PostgreSQL and a local report volume remain
the backend persistence layer. The agent has its own filesystem outbox and only
outbound HTTP connectivity.

## Components

```text
AgentApplication
  -> AgentConfigLoader -> configured TargetRegistry
  -> AgentScheduler
      -> ScanCoordinator -> TrivyImageScanner -> FileAgentOutbox
      -> UploadCoordinator
          -> AssetCache
          -> VulnFlowHttpClient
          -> FileAgentOutbox
      -> uploaded-only cleanup

AssetController PUT /api/v1/assets/resolve
  -> AssetService
      -> AssetIdentityRepository -> INSERT ON CONFLICT
      -> AssetRepository

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

## Agent-to-findings flow

1. Startup validates every required setting and target, then executes a bounded
   `trivy --version` without a shell.
2. A scan cycle takes only configured targets, applies global and per-target
   concurrency guards, and invokes Trivy with an argument list.
3. The bounded valid JSON is normalized only by removing the volatile root
   `CreatedAt`, then copied to an atomically created UUID outbox directory.
4. Scanning succeeds even when the backend is unavailable. A cached asset ID is
   optional until the upload cycle can resolve it.
5. Upload atomically claims a ready item, verifies SHA-256, and calls the
   idempotent asset resolver when needed.
6. The client sends multipart JSON to the existing ingestion endpoint. `200`
   and `202` become `UPLOADED`; network/`5xx` failures set persisted backoff;
   functional `4xx` failures dead-letter.
7. The unchanged backend queue and worker turn the report into findings.

The agent scheduler, scanner, uploader, target registry, outbox, and client are
separate interfaces or focused classes. There is no inbound agent API.

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

- `UNIQUE(type, external_reference)` plus `INSERT ... ON CONFLICT DO NOTHING`
  resolves one asset under concurrent agents; an existing asset keeps its name.
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
- One scan cycle and a per-target stable-key set prevent target overlap. A fixed
  executor caps global Trivy concurrency.
- Outbox claims are synchronized and move persisted state to `UPLOADING` before
  HTTP. A second upload cycle cannot claim that item.

## Recovery and backoff

Stale `PROCESSING` jobs are selected by `lockedAt`. If attempts remain they move
to `RETRY_WAIT`, otherwise to `DEAD_LETTER`. Recovery uses row locks and is
idempotent because the first execution changes the status. Both paths clear the
claim token before releasing the transaction.

The default retry sequence is 5 seconds, 30 seconds, and 2 minutes. Retries set
`availableAt`; no worker sleeps while waiting.

## CI/CD and production runtime

```text
pull request
  -> backend mvn verify -> mandatory PostgreSQL integration reports
  -> agent mvn verify
  -> local backend and agent image builds

push to main
  -> the same verification boundary
  -> GHCR backend:<commit-sha> + agent:<commit-sha>
  -> protected production environment and enable switch
  -> pinned-host SSH + deployment-file synchronization
  -> Docker Compose pull/up -> Flyway startup -> health gate
  -> accept release or restore previous image manifest
```

CI owns compilation, tests, and image publication. The VPS is a runtime host;
it receives only the `deploy/` bundle and immutable image references. Its
`runtime/.env.prod` and `runtime/targets.yml` remain outside the synchronized
directory. GitHub Actions does not transmit the VulnFlow API key or PostgreSQL
password.

The production Compose topology is:

```text
host Nginx/TLS
  -> 127.0.0.1:8080 -> backend
                           -> PostgreSQL (private network, named volume)
  agent (private network) -> backend
    -> Trivy -> named outbox/cache volume
    -> read-only targets file
```

PostgreSQL has no host port. The backend report store and agent data are named
volumes. The agent does not receive the Docker socket, so this topology supports
registry-accessible image targets only. Host-local Docker images require the
separately documented systemd model and are not silently enabled by CI/CD.

Deployment serialization exists at two levels: GitHub uses the
`vulnflow-production` concurrency group with cancellation disabled, and the VPS
uses a non-blocking `flock`. The candidate release manifest must contain the two
GHCR image references and one matching 40-character commit SHA. The previous
manifest is retained before Compose is updated.

Rollback restores application image references and repeats the health check; it
does not reverse PostgreSQL. Flyway runs during normal backend startup, so
production migrations must remain compatible with the preceding release.
Explicit volume names let an operator adopt existing VPS volumes without
recreation. No deployment command invokes `down`, volume deletion, or pruning.

## Future adapter boundaries

```text
LocalFileReportStorage -> S3ReportStorage
IngestionJobRepository/claim service -> SQS delivery adapter
LocalIngestionWorker -> Lambda handler
```

The agent's future transport seam is:

```text
FileAgentOutbox -> presigned S3 uploader -> SQS submission
```

These are migration seams only. No AWS SDK or cloud resource is used in 0.3.1.
SQS will use receipt handles and visibility timeouts rather than copying the
current PostgreSQL row-locking protocol. S3 calls must not be introduced under
long-held database locks.
