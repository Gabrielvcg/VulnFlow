# Security

## Authentication boundary

Every `/api/v1/**` endpoint requires the configured `X-API-Key`. Comparison is
constant-time and the key is never logged. Health, metrics, OpenAPI, and Swagger
remain public for the localhost-first phase. Docker binds backend and PostgreSQL
only to `127.0.0.1` by default.

This is provisional machine-to-machine authentication. It does not provide
users, roles, per-client attribution, rotation, or authorization. A future
human interface should use OIDC or JWT.

## Upload controls

- Multipart and application limits default to 10 MB.
- Only JSON media types are accepted.
- SHA-256 deduplication is scoped to an asset.
- The original filename is sanitized and stored only as scan metadata.
- Client filenames never become storage paths.
- Trivy structure and required fields are validated by the worker.
- Stored bytes are checked against the scan SHA-256 before JSON parsing.
- Vulnerability identifiers are rejected rather than silently truncated.
- Descriptions are limited to 8,000 characters by default.

The HTTP endpoint deliberately accepts syntactically or semantically invalid
JSON as durable work. Validation occurs asynchronously and such jobs terminate
directly in `DEAD_LETTER` without pointless retries.

Payload-integrity errors also terminate without retry. Public job responses use
a generic bounded reason and expose neither the expected nor actual hash.

## Agent security boundary

- The agent scans only targets from a bounded validated YAML file. Duplicate,
  blank, oversized, and unsupported targets fail startup.
- Trivy receives an immutable argument list. Image references never pass
  through a shell and output paths are internal random temporaries.
- Startup fails if `trivy --version` fails. Each scan has a timeout, bounded
  stdout/stderr capture, report byte limit, and JSON validation.
- The API key exists only in process configuration and the HTTP header. Safe
  `--check`, `--status`, logs, target YAML, outbox metadata, and asset cache do
  not contain it.
- Structured logs use target names rather than image references, because a
  reference could accidentally contain registry credentials.
- The outbox verifies SHA-256 before upload, refuses path traversal, persists
  through atomic renames, and never applies retention to unuploaded evidence.
- The systemd and Docker examples run as dedicated non-root identities. The
  Docker socket is not mounted.

The top-level Trivy `CreatedAt` generation timestamp is removed before outbox
storage to provide stable deduplication. This field is not used by backend
parsing; vulnerability and artifact content are preserved.

## CI/CD and VPS boundary

- Pull requests can verify and build images but cannot publish or deploy.
- Publication and deployment require `refs/heads/main`; production additionally
  requires `VPS_DEPLOY_ENABLED=true` in the protected GitHub environment.
- Workflow permissions default to read-only contents. Only the publishing job
  receives `packages: write`; the deploy job has no package-write permission.
- Both deployed image references use the exact 40-character commit SHA. The VPS
  script rejects mutable-only or malformed release references.
- SSH uses a dedicated private key, `IdentitiesOnly`, pinned known-hosts data,
  and `StrictHostKeyChecking=yes`. No runtime `ssh-keyscan` result is trusted.
- Temporary runner key and known-hosts files are permission-restricted and
  removed in an unconditional cleanup step.
- The workflow synchronizes only `deploy/`. API and database secrets remain in
  a mode-`600` VPS runtime file outside that tree and are not passed as command
  arguments or workflow outputs.
- PostgreSQL has no published port. The backend binds only to loopback for host
  Nginx, and production OpenAPI/Swagger default to disabled.
- The backend and agent drop Linux capabilities and use read-only root
  filesystems. The non-root agent has no Docker socket.
- GitHub and host-side locks prevent overlapping production updates. Deployment
  never uses `down -v`, volume deletion, or broad image pruning.

The deploy user's access to a conventional Docker daemon remains highly
privileged even when the user is not `root`. Prefer rootless Docker or a tightly
restricted command wrapper when operationally feasible. GHCR tokens, when
needed for private packages, should be read-only and dedicated to deployment.
Protect the `production` environment with reviewers and restrict who can change
its variables, secrets, and branch policy.

Container rollback cannot undo a committed Flyway migration. A migration that
is incompatible with the preceding image can make automatic rollback fail even
when the old images are available. This is controlled by backward-compatible
migration design and database backup/recovery procedures, not by pretending the
deployment script provides a distributed rollback.

## Local payload storage

`LocalFileReportStorage`:

- generates unpredictable internal keys;
- normalizes and verifies paths beneath one configured root;
- rejects absolute paths and traversal;
- writes a temporary file before moving it into place;
- uses an atomic move when the filesystem supports it;
- never logs report content or physical paths;
- exposes neither keys nor payload content through the API.

The Docker volume is persistent but not an encrypted object store. Host access,
disk encryption, backup, quotas, malware handling, and secure retention remain
operator responsibilities. Completed payload deletion is intentionally deferred
until a reviewed retention policy exists.

## Queue and error safety

Database constraints restrict job states and counters. Error text persisted in
jobs and scans is generic and limited to 500 characters. Full exceptions may be
logged for operators but are not included in HTTP responses.

Every processing claim receives a random UUID token. Attempt counters only
track retry budget; completion and failure compare the token so a token from a
previous redrive cannot affect the current worker. Recovery and redrive clear
active tokens. `SKIP LOCKED`, unique scan/job constraints, token fencing, and
transactional finding replacement are covered by PostgreSQL integration tests.

Unknown processing exceptions are conservatively non-retryable. Only explicitly
transient storage errors and transient/recoverable database causes retry. Full
exception types remain internal logs; persisted reasons are generic and bounded.

## Remaining risks

- A shared API key has broad access and no rate limiting.
- Public local metrics may disclose operational counts if host binding changes.
- Payload retention can exhaust disk space.
- The API still reads the bounded multipart file into memory.
- Local storage is not suitable for horizontally scaled hosts without a shared
  filesystem; S3 is the planned durable replacement.
- Dependency and container scanning should run in CI and release workflows.
- A compromised Trivy executable has the permissions of the dedicated agent
  user; binary provenance and updates remain operator responsibilities.
- Filesystem atomicity depends on a local filesystem that supports atomic moves.
- One outbox directory does not support multiple agent processes or shared NFS.
- Registry credentials inherited by Trivy require separate host-level secret
  management and are outside VulnFlow's target YAML.
- A compromised GitHub Actions dependency, GHCR account, deploy key, or Docker
  daemon can compromise production; actions are commit-pinned, while image
  signing/attestation remains future hardening.
- Automatic rollback has no target on the first deployment and cannot reverse
  Flyway data or schema changes.

## Prepared AWS boundary

### 0.4.1 execution controls

AWS SDK clients use bounded connect/API/socket timeouts and standard SDK retries. Bucket, prefix, queue
URL, table name, payload size, event identity, cursor, and finding count are validated. Events carry only
generated identifiers, hash, scanner, and an internal logical S3 key; they contain no report body,
filename, API key, credential, or personal data. Lambda logs message identifiers and exception classes,
not event/report bodies. Failed-result text is fixed and bounded.

Terraform now enables managed encryption for DynamoDB and scopes Lambda IAM to one table/index in
addition to the existing object prefix, queue, and log group. It declares no VPC or PostgreSQL access.

For a future non-AWS VPS, prefer IAM Roles Anywhere short-lived credentials. Platform OIDC is better when
a trustworthy issuer exists; a narrow proxy/presigned-upload service can reduce VPS permissions further.
Long-lived IAM user keys are the least-preferred fallback because rotation and leak response become host
responsibilities. No credential mechanism is configured in 0.4.1.

Remaining AWS-specific risks include at-least-once duplicate publication, S3 orphans in the upload/commit
crash window, an outbox `FAILED` state distinct from SQS DLQ visibility, hot scan partitions for unusually
large reports, lifecycle deletion before delayed processing, and lack of human authorization/rate limits.

AWS adapters are inactive unless the `aws` profile is explicit. S3 keys are
generated internally under a validated prefix, payload size is bounded, and an
application SHA-256 metadata value is checked after download in addition to SDK
checksum handling. SQS messages contain identifiers and a logical key only;
they exclude report bytes, filenames, paths, credentials, and API keys.

Terraform blocks S3 public access, enables managed encryption for S3/SQS,
limits retention/concurrency, and scopes Lambda IAM to one object prefix, one
queue, and one log group. It intentionally declares no network perimeter or
database access. A future PostgreSQL result provider needs TLS, secret retrieval,
connection caps, event-id uniqueness, and a reviewed connectivity model before
the apply safety gate can open.
