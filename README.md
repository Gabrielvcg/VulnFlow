# VulnFlow

VulnFlow 0.2.0 is a local-first Spring Boot API that accepts Trivy reports,
stores them durably, and processes them asynchronously through a PostgreSQL job
queue. PostgreSQL remains the source of truth for assets, scans, jobs, and
findings; report bytes live behind the `ReportStorage` abstraction.

## Current architecture

```text
Client
  | POST /api/v1/scans/trivy
  v
API key filter -> validation -> SHA-256
  | short registration transaction
  +-> Scan(RECEIVED)
  +-> LocalFileReportStorage
  +-> IngestionJob(PENDING)
  v
202 Accepted

Scheduled local worker
  | FOR UPDATE SKIP LOCKED, short claim transaction
  v
Job(PROCESSING, claimToken) + Scan(PROCESSING)
  | no database lock held
  +-> load payload -> verify SHA-256 -> Trivy parser -> risk calculation
  | atomic completion transaction
  v
Findings + Scan(COMPLETED) + Job(COMPLETED)
  or RETRY_WAIT / DEAD_LETTER
```

The backend is one modular monolith. No AWS service, message broker, frontend,
or additional runtime container is required.

## Run locally

Requirements: Docker Desktop or Docker Engine with Compose.

```powershell
Copy-Item .env.example .env
# Change VULNFLOW_API_KEY in .env for anything beyond an isolated demo.
docker compose up --build -d
docker compose ps
```

Default local endpoints:

- API: `http://127.0.0.1:8080`
- Swagger UI: `http://127.0.0.1:8080/swagger-ui.html`
- Health: `http://127.0.0.1:8080/actuator/health`
- Metrics catalog: `http://127.0.0.1:8080/actuator/metrics`
- PostgreSQL: `127.0.0.1:5432`

Only health, metrics, OpenAPI, and Swagger are public. Every `/api/v1/**`
endpoint requires `X-API-Key`.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `VULNFLOW_API_KEY` | local demo value in Compose | Machine-to-machine API authentication |
| `VULNFLOW_MAX_FILE_SIZE` | `10MB` | Multipart and ingestion byte limit |
| `VULNFLOW_MAX_DESCRIPTION_LENGTH` | `8000` | Maximum imported description length |
| `VULNFLOW_REPORT_STORAGE_DIRECTORY` | `./data/reports` | Local payload root; Docker uses a named volume |
| `VULNFLOW_WORKER_ENABLED` | `true` | Enables scheduled polling; set `false` for debugging |
| `VULNFLOW_WORKER_POLL_INTERVAL` | `2s` | Delay between polling cycles |
| `VULNFLOW_WORKER_BATCH_SIZE` | `5` | Maximum claims per cycle |
| `VULNFLOW_WORKER_MAX_ATTEMPTS` | `3` | Maximum processing attempts |
| `VULNFLOW_WORKER_STALE_TIMEOUT` | `15m` | Processing lease timeout |
| `VULNFLOW_WORKER_BACKOFF` | `5s,30s,2m` | Deterministic retry delays |

The API key is provisional. A future human-facing interface should use OIDC or
JWT with explicit authorization.

## Asynchronous ingestion

```http
POST /api/v1/scans/trivy?assetId={assetId}
X-API-Key: configured-value
Content-Type: multipart/form-data
```

A new report returns immediately after durable registration:

```json
{
  "scanId": "uuid",
  "jobId": "uuid",
  "assetId": "uuid",
  "scanStatus": "RECEIVED",
  "jobStatus": "PENDING",
  "outcome": "ACCEPTED"
}
```

Response semantics:

| Existing state | HTTP | Outcome | Action |
| --- | --- | --- | --- |
| No matching scan | `202` | `ACCEPTED` | Store one payload and create one job |
| `PENDING` or `RETRY_WAIT` | `202` | `ALREADY_QUEUED` | Reuse scan and job |
| `PROCESSING` | `202` | `ALREADY_PROCESSING` | Reuse scan and job |
| `COMPLETED` | `200` | `DUPLICATE` | No payload, job, or finding is added |
| `FAILED` with `DEAD_LETTER` job | `200` | `DEAD_LETTER` | No automatic retry; use redrive |
| Non-completed legacy scan without job | `202` | `ACCEPTED` | Store the re-uploaded payload and create one job |

Deduplication remains enforced by `UNIQUE(asset_id, content_hash)`. One job per
scan is enforced by `UNIQUE(scan_id)`.

## Scan and job states

Scans describe domain processing state:

```text
RECEIVED -> PROCESSING -> COMPLETED
    ^             |
    |             +-> FAILED when the job reaches DEAD_LETTER
    +---- retry or redrive
```

Jobs describe queue execution state:

```text
PENDING -> PROCESSING -> COMPLETED
              |
              +-> RETRY_WAIT -> PROCESSING
              +-> DEAD_LETTER -> PENDING (manual redrive)
```

Invalid JSON, invalid Trivy structure, invalid required fields, and a missing
payload, payload-integrity failures, deterministic processing failures, and
unknown runtime failures are non-retryable. Explicitly transient storage and
database availability failures use the configured backoff without
`Thread.sleep`. At exhaustion the job becomes `DEAD_LETTER` and its scan becomes
`FAILED`.

## Job API

- `GET /api/v1/ingestion-jobs?status=&scanId=&page=&size=`
- `GET /api/v1/ingestion-jobs/{jobId}`
- `POST /api/v1/ingestion-jobs/{jobId}/redrive`

Redrive accepts only `DEAD_LETTER`, verifies that the payload exists, invalidates
the previous claim token, resets the attempt counter, sets the job to `PENDING`
and the scan to `RECEIVED`, and returns `202`. The next claim always creates a
new UUID token. Any other state returns `409`.

Job responses never expose payload keys, physical paths, report content,
credentials, or stack traces. Lists use `createdAt DESC, id DESC` by default.

## Transaction and locking boundaries

1. Registration serializes matching hashes, stores the payload, creates the
   `RECEIVED` scan and `PENDING` job, and commits before returning `202`.
2. Claim uses `FOR UPDATE SKIP LOCKED`, increments the attempt and updates the
   scan to `PROCESSING` while generating a new claim-token UUID in a short
   transaction.
3. The payload is loaded, checked against the scan SHA-256, and parsed after the
   claim transaction has committed.
4. Findings, `Scan(COMPLETED)`, and `Job(COMPLETED)` commit atomically.
5. Failure and stale recovery transitions use independent short transactions.

Every detached claim carries both an attempt number and a claim token.
`attemptCount` is a retry counter and may reset on manual redrive; `claimToken`
is a non-reusable fencing generation. Completion and failure require the current
token, so a stale worker remains rejected even when attempt numbers match. This
provides at-least-once processing with idempotent finalization.

## Upgrade policy

Flyway V4 defines the transition from 0.1.1 and early 0.2.0 databases:

- historical `COMPLETED` scans without jobs remain valid duplicates;
- historical `RECEIVED` or `PROCESSING` scans without jobs become `FAILED`
  because their original payload cannot be reconstructed;
- re-uploading a non-completed legacy scan stores the supplied payload and
  creates exactly one new job;
- an early-0.2.0 `PROCESSING` job has its old claim invalidated and moves to
  `RETRY_WAIT`, or `DEAD_LETTER` when no attempt remains.

The migration cannot resume historical work whose report bytes were never
persisted. See [migration policy](docs/migrations.md).

## Storage

`LocalFileReportStorage` creates internal keys unrelated to the uploaded
filename, verifies every resolved path remains under its configured root,
writes a temporary file, and uses an atomic move when supported. The original
filename is scan metadata only. Before parsing, the worker recomputes SHA-256
and compares it with the scan hash; altered content dead-letters without being
parsed.

Completed payloads are intentionally retained in 0.2.0. Retention, cleanup,
capacity limits, backup, and encryption policies remain future work.

## Metrics

- `vulnflow.ingestion.jobs.accepted`
- `vulnflow.ingestion.jobs.completed`
- `vulnflow.ingestion.jobs.retried`
- `vulnflow.ingestion.jobs.dead_letter`
- `vulnflow.ingestion.processing.duration`
- `vulnflow.ingestion.jobs.pending`
- `vulnflow.ingestion.jobs.retry`
- `vulnflow.ingestion.jobs.dead_letter.current`

## Verification and demo

```powershell
Set-Location backend
.\mvnw.cmd test
.\mvnw.cmd verify
Set-Location ..

$env:VULNFLOW_API_KEY = "local-development-only-api-key"
& "C:\Program Files\Git\bin\bash.exe" -lc `
  'cd /c/Users/GabrielVG/Desktop/PROYECTOS/secscan && ./scripts/demo.sh'
```

`mvn test` runs the Docker-independent unit suite. `mvn verify` is the required
release/CI command: it starts PostgreSQL with Testcontainers and fails if Docker
is unavailable. The demo polls with a timeout, shows a completed report,
verifies a duplicate, waits for an invalid report, alters that retained payload,
redrives it, and verifies integrity dead-lettering.

## Documentation

- [Architecture](docs/architecture.md)
- [Security](docs/security.md)
- [Migration policy](docs/migrations.md)
- [AWS roadmap](docs/aws-roadmap.md)
- [ADR-007 PostgreSQL queue](docs/decisions/ADR-007-postgresql-persistent-job-queue.md)
- [ADR-008 local storage](docs/decisions/ADR-008-local-report-storage.md)
- [ADR-009 at-least-once processing](docs/decisions/ADR-009-at-least-once-processing.md)

AWS remains intentionally deferred. The local `ReportStorage`, persistent job
queue, and worker boundaries are preparation for later S3, SQS, and Lambda
adapters, not cloud integrations in this release.
