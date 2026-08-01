# VulnFlow

VulnFlow 0.4.1 completes an executable AWS ingestion path without deploying or
contacting AWS. The local/VPS mode remains the default and unchanged. The
explicit `aws` profile selects S3 payload storage, a recoverable PostgreSQL SQS
publication outbox, the shared Lambda processor, and a DynamoDB result store.
Terraform remains limited to offline format/init/validate in this phase.

## Current architecture

```text
Configured Linux host
  | VulnFlow Agent: Trivy -> durable local outbox -> asset resolution
  v
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
  +-> VulnerabilityReportProcessor (SHA-256 -> Trivy -> risk -> normalized findings)
  | atomic completion transaction
  v
Findings + Scan(COMPLETED) + Job(COMPLETED)
  or RETRY_WAIT / DEAD_LETTER
```

The backend remains one modular monolith. The optional agent is outbound-only
and has no Spring/backend compile-time dependency. Local operation creates no
AWS client and requires no AWS credentials, service, broker, or cloud runtime.

The AWS profile follows a different, explicit path:

```text
HTTP upload -> S3 -> PostgreSQL Scan + publication outbox -> 202
                         |
                         v (short claim, then no DB lock)
                        SQS -> Lambda -> shared processor -> DynamoDB
                                                            |
                                                            v
                                      GET /api/v1/scans/{id}/findings
```

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

The following settings are bound only when the explicit Spring profile `aws`
is active; local and VPS `prod` operation creates no AWS SDK clients:

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `AWS_REGION` | `eu-west-1` | SDK client region |
| `VULNFLOW_S3_BUCKET` | none | Private report bucket; required by `aws` profile |
| `VULNFLOW_S3_PREFIX` | `reports` | Validated logical object prefix |
| `VULNFLOW_SQS_QUEUE_URL` | none | Ingestion queue URL; required by `aws` profile |
| `VULNFLOW_DYNAMODB_TABLE` | none | Result table; required by `aws` profile |
| `VULNFLOW_DYNAMODB_MAX_FINDINGS` | `100000` | Maximum normalized findings per event |
| `VULNFLOW_MAX_PAYLOAD_BYTES` | `10485760` | S3 adapter/Lambda bounded-read limit |
| `VULNFLOW_AWS_API_TIMEOUT` | `10s` | SDK API call/socket timeout |
| `VULNFLOW_AWS_CONNECTION_TIMEOUT` | `3s` | SDK connection timeout |
| `VULNFLOW_AWS_OUTBOX_ENABLED` | `true` | Enables scheduled SQS publication in `aws` profile |
| `VULNFLOW_AWS_OUTBOX_BATCH_SIZE` | `10` | Maximum publication claims per poll |
| `VULNFLOW_AWS_OUTBOX_MAX_ATTEMPTS` | `5` | Publication retry budget |
| `VULNFLOW_AWS_OUTBOX_STALE_TIMEOUT` | `2m` | Recovery timeout for abandoned claims |
| `VULNFLOW_AWS_OUTBOX_BACKOFF` | `5s,30s,2m,10m` | Publication retry delays |

The API key is provisional. A future human-facing interface should use OIDC or
JWT with explicit authorization.

## Continuous scanning agent

Targets are declared explicitly in YAML:

```yaml
targets:
  - name: alpine-demo
    type: CONTAINER_IMAGE
    reference: alpine:3.15
```

Build and validate the agent:

```powershell
Set-Location agent
.\mvnw.cmd verify
Copy-Item targets.example.yml targets.yml
$env:VULNFLOW_API_URL = "http://127.0.0.1:8080/"
$env:VULNFLOW_API_KEY = "configured-value"
$env:VULNFLOW_AGENT_ID = "developer-machine"
$env:VULNFLOW_TARGETS_FILE = (Resolve-Path targets.yml)
java -jar target/vulnflow-agent-0.4.1.jar --check
java -jar target/vulnflow-agent-0.4.1.jar --once
java -jar target/vulnflow-agent-0.4.1.jar --status
```

The default daemon mode schedules isolated scan, upload, and cleanup cycles.
Reports enter a filesystem outbox before network access, survive restarts, and
are checked against their SHA-256 before upload. `UPLOADED` reports are retained
for seven days by default; pending, retrying, and dead-letter reports are never
deleted automatically. See [the agent guide](docs/agent.md) for all variables,
systemd, Docker, security, capacity, and failure behavior.

## Idempotent asset resolution

```http
PUT /api/v1/assets/resolve
X-API-Key: configured-value
Content-Type: application/json

{
  "name": "alpine-demo",
  "type": "CONTAINER_IMAGE",
  "externalReference": "alpine:3.15"
}
```

The endpoint returns `201` for a new `(type, externalReference)` identity and
`200` with the existing asset otherwise. The original stored name is conserved.
Flyway V5 and PostgreSQL `ON CONFLICT` make concurrent resolution atomic. The
existing `POST /api/v1/assets` remains available; duplicate external identity
now returns `409`.

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

Backend-completed local payloads remain intentionally retained. Retention, cleanup,
capacity limits, backup, and encryption policies remain future work.

## Production VPS delivery

Pull requests run backend and agent verification plus local image builds. A
successful push to `main` additionally publishes both images to GHCR under the
exact 40-character commit SHA. Deployment remains gated by the protected
GitHub `production` environment and `VPS_DEPLOY_ENABLED=true`.

The production Compose bundle runs PostgreSQL, backend, and agent as separate
services. PostgreSQL is private, the backend binds only to VPS loopback, and
the non-root agent has no Docker socket. The workflow verifies a pinned SSH host
key, synchronizes only versioned deployment files, and never overwrites the
VPS-only runtime environment or target file. Named PostgreSQL, report, and
agent-outbox volumes are preserved.

See [the VPS deployment runbook](docs/operations/vps-deployment.md) for one-time preparation,
GitHub variables and secrets, Nginx/TLS, exact-SHA deployment, health checks,
automatic rollback, emergency recovery, and the deployment pause switch. No
real VPS deployment is required to validate this repository configuration.

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
.\backend\mvnw.cmd -f pom.xml test -DskipITs
.\backend\mvnw.cmd -f pom.xml verify

$env:VULNFLOW_API_KEY = "local-development-only-api-key"
& "C:\Program Files\Git\bin\bash.exe" -lc `
  'cd /c/Users/GabrielVG/Desktop/PROYECTOS/secscan && ./scripts/demo.sh'
```

`mvn test` runs the Docker-independent unit suite. `mvn verify` is the required
release/CI command: it starts PostgreSQL with Testcontainers and fails if Docker
is unavailable. The demo polls with a timeout, shows a completed report,
verifies a duplicate, waits for an invalid report, alters that retained payload,
redrives it, and verifies integrity dead-lettering.

The deterministic agent end-to-end demo does not require Trivy:

```powershell
& "C:\Program Files\Git\bin\bash.exe" -lc 'cd /c/Users/GabrielVG/Desktop/PROYECTOS/secscan && ./scripts/agent-e2e-fake.sh'
```

When Trivy is already installed, `scripts/agent-demo.sh` scans
`VULNFLOW_DEMO_IMAGE` (default `alpine:3.15`) and repeats the cycle to show
deduplication. The script never installs Trivy and does not assume a fixed
vulnerability count.

## Documentation

- [Architecture](docs/architecture.md)
- [AWS-ready architecture](docs/aws-architecture.md)
- [AWS readiness assessment](docs/aws-readiness-assessment.md)
- [Ingestion event V1](docs/contracts/ingestion-event-v1.md)
- [AWS cost model](docs/aws-cost-model.md)
- [Temporary AWS runbook](docs/aws-temporary-deployment-runbook.md)
- [Agent operations](docs/agent.md)
- [Security](docs/security.md)
- [CI/CD verification](docs/operations/cicd.md)
- [VPS deployment](docs/operations/vps-deployment.md)
- [Migration policy](docs/migrations.md)
- [AWS roadmap](docs/aws-roadmap.md)
- [ADR-007 PostgreSQL queue](docs/decisions/ADR-007-postgresql-persistent-job-queue.md)
- [ADR-008 local storage](docs/decisions/ADR-008-local-report-storage.md)
- [ADR-009 at-least-once processing](docs/decisions/ADR-009-at-least-once-processing.md)
- [ADR-010 VPS agent](docs/decisions/ADR-010-vps-agent.md)
- [ADR-011 agent outbox](docs/decisions/ADR-011-agent-persistent-outbox.md)
- [ADR-012 safe Trivy execution](docs/decisions/ADR-012-safe-external-process-execution.md)
- [ADR-013 VPS CI/CD](docs/decisions/ADR-013-vps-cicd.md)
- [ADR-019 DynamoDB result store](docs/decisions/ADR-019-dynamodb-result-store.md)
- [ADR-020 AWS publication outbox](docs/decisions/ADR-020-aws-publication-outbox.md)
- [ADR-021 local/AWS sources of truth](docs/decisions/ADR-021-local-and-aws-sources-of-truth.md)
- [ADR-022 event idempotency](docs/decisions/ADR-022-event-idempotency-policy.md)
- [ADR-023 VPS AWS credentials](docs/decisions/ADR-023-vps-aws-credentials.md)

AWS deployment remains intentionally deferred. The code and Terraform are
offline execution artifacts only: 0.4.1 did not use an AWS account, create
resources, or run Terraform plan/apply/destroy. Any future apply requires the
explicit `result_store_provider="dynamodb"` input and the documented review.
