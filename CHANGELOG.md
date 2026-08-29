# Changelog

## 0.4.9 - 2026-08-29

- Added a separate immutable React console image with a static, sanitized public case study and a private operational surface for assets, scans, findings, runtime health, users, targets, and audit events.
- Added JDBC sessions, CSRF, BCrypt 12 passwords, `ADMIN`/`OPERATOR` authorization, temporary-password first access, login throttling, and an additive PostgreSQL UI control-plane migration.
- Added allowlisted on-demand scan requests with quotas, Agent health and capacity gates, leased claims, fencing tokens, heartbeat recovery, and backward-compatible Trivy uploads.
- Added read-only SQS/DLQ telemetry permission, safe publication retry, production web routing and security headers, three-image release manifests, and web/backend/Agent health gates.
- Reduced uploaded Agent outbox retention to 24 hours while preserving pending and dead-letter reports, and kept all UI and scan execution flags disabled by default.
- Made the immutable deployer compatible with the pre-UI three-line release manifest during the first-console migration, while keeping new release validation strict.
- Replaced the oversized inline Roles Anywhere session policy with the same least-privilege managed policy so temporary sessions remain within AWS packed-policy limits.

## 0.4.8 - 2026-08-02

- Enforced the real `arn:aws:iam::160172542031:mfa/movil` device in the exact-user
  Terraform operator trust and documented the temporary `vulnflow-admin` role
  session boundary.
- Activated encrypted, versioned S3 remote state with native lockfiles and
  tightened the state/application operator policies to the provider metadata,
  and Lambda event-source tagging actions demonstrated by reviewed applies;
  one-time verification and service-linked-role permissions were removed after
  use.
- Applied and verified the VulnFlow-only S3, SQS/DLQ, DynamoDB, Lambda,
  CloudWatch, IAM, and IAM Roles Anywhere resources in `eu-west-1`, without
  destroy actions or changes to unrelated infrastructure.
- Activated `prod,aws` on the VPS with short-lived Roles Anywhere credentials,
  disabled the local worker, retained PostgreSQL and all local volumes, and
  proved the agent-to-public-API path, duplicate handling, bounded retries,
  DLQ redrive, recovery, health checks, and local-mode rollback.

## 0.4.7 - 2026-08-01

- Replaced the unexecuted IAM Identity Center design with a single-account,
  exact-user `VulnFlowTerraformOperator` AssumeRole flow that preserves the AWS
  Free Plan and keeps Organizations disabled.
- Added an isolated, plan-only identity bootstrap for one operator role, two
  scoped policies, and their attachments, with optional real-MFA enforcement
  and no credential or access-key management.
- Hardened the Terraform identity preflight to accept only the exact STS role
  session and expanded CI, tests, runbooks, and policy documentation for the
  identity, state, application, and future Roles Anywhere boundaries.

## 0.4.6 - 2026-08-01

- Prepared an opt-in IAM Roles Anywhere identity with a trust-anchor-bound,
  certificate-CN-bound backend role and a duplicate least-privilege session
  policy; it remains disabled in the default application plan.
- Added digest-pinned Java container bases, the checksum-pinned AWS signing
  helper, temporary-session health gate,
  explicit AWS Compose override, and safe VPS runtime preparation flow without
  storing access keys.
- Completed both remote-state backend examples and added an Identity Center
  preflight that rejects persistent IAM-user credentials before Terraform work.

## 0.4.5 - 2026-08-01

- Added an isolated Terraform bootstrap for the private, encrypted, versioned
  S3 state bucket with public-access blocking, TLS enforcement, and destruction
  guards.
- Prepared the inactive application S3 backend configuration with native
  lockfiles and documented the two-phase state migration, scoped human
  permissions, Identity Center access, and future Roles Anywhere boundary.

## 0.4.4 - 2026-08-01

- Replaced the Lambda reserved-concurrency default with an SQS event-source
  maximum concurrency of two, keeping the demo bounded while allowing plans
  to work in AWS accounts whose regional concurrency quota is below 100.

## 0.4.3 - 2026-08-01

- Return the controlled API error envelope with HTTP 404 for missing static
  resources, including disabled OpenAPI endpoints, instead of treating them as
  unexpected HTTP 500 failures.

## 0.4.2 - 2026-08-01

- Routed Trivy OCI download temporary files to the durable agent volume so the
  production scan is not constrained by the container's 64 MiB `/tmp` tmpfs.
- Corrected the VPS bootstrap permissions for the non-secret targets file so
  the non-root containerized agent can read its bind-mounted configuration.

## 0.4.1 - 2026-08-01

- Added a production AWS SDK DynamoDB result store with event-identity fencing, deterministic finding batches, hidden partial writes, atomic commit markers, failed-result persistence, and paginated queries.
- Added the AWS-profile S3 upload and PostgreSQL publication outbox path, including `SKIP LOCKED` claims, stale-claim recovery, bounded backoff, claim-token fencing, and SQS publication outside database transactions.
- Added AWS result endpoints backed by DynamoDB while preserving the unchanged filesystem/PostgreSQL worker path under local and VPS profiles.
- Hardened the Lambda batch handler with finalized-event short-circuiting, permanent/transient classification, safe failed-result storage, and per-record retry responses.
- Added on-demand encrypted DynamoDB Terraform, table-scoped Lambda IAM, optional DLQ depth alarm, explicit `result_store_provider=dynamodb` deployment gate, and updated offline runbooks and ADRs.
- Added mocked DynamoDB coverage and a PostgreSQL-backed, fully simulated HTTP-to-Lambda-to-query demo with no AWS endpoint access.

## 0.4.0 - 2026-07-31

- Extracted the infrastructure-neutral report processor and versioned V1 ingestion event into a shared Maven module used by both the local worker and the SQS Lambda handler.
- Added explicit `ReportStorage`, `ProcessingResultStore`, and `IngestionMessagePublisher` ports plus disabled-by-default, mock-tested S3 and SQS AWS SDK adapters.
- Added a separately packaged Java Lambda batch handler with per-record failures and result-store idempotency boundaries, without local polling, SQL claim locks, application backoff, or manual dead-letter routing.
- Added conservative Terraform modules for private encrypted S3, SQS/DLQ, Lambda, least-privilege IAM, bounded CloudWatch retention, and the SQS event source mapping, with a fail-closed result-store readiness gate.
- Expanded CI to verify all Java modules, contracts, PostgreSQL integration tests, container artifacts, and Terraform formatting/validation without AWS credentials or AWS deployment.
- Added AWS coupling assessment, event contract, architecture/cost/runbook guidance, result-storage analysis, and ADRs for the shared core and runtime adapter boundaries.

## 0.3.1 - 2026-07-31

- Added a gated GitHub Actions CI/CD flow that verifies backend and agent,
  requires non-skipped PostgreSQL integration tests, publishes immutable GHCR
  images by commit SHA, and deploys only from `main`.
- Added a runtime-only production Compose bundle for private PostgreSQL,
  loopback-only backend, and a non-root containerized agent without a Docker
  socket, while preserving explicitly named data volumes.
- Added strict pinned-host SSH verification, commit-pinned actions, minimum
  workflow permissions, protected production concurrency, VPS-only runtime
  secrets, and an explicit deployment enable switch.
- Added health-gated deployment with bounded diagnostics and automatic rollback
  to the previous image manifest without promising Flyway database rollback.
- Added VPS bootstrap, Nginx/TLS, exact-SHA release, emergency recovery, agent,
  migration, secret, and deployment-pause documentation plus ADR-013.
- Enforced Linux line endings for shell entry points and normalized the agent
  Maven wrapper during image builds so Windows worktrees remain reproducible.

## 0.3.0 - 2026-07-31

- Added an independent Java 17 Linux agent that scans explicit container-image
  targets with Trivy and supports daemon, `--once`, `--check`, and `--status`
  operation.
- Added a durable bounded filesystem outbox with atomic writes, restart
  recovery, SHA-256 verification, persisted backoff, dead letter handling, and
  uploaded-only retention.
- Added safe shell-free Trivy execution with configurable path and timeout,
  bounded process output and report size, JSON validation, global concurrency,
  and per-target overlap protection.
- Added idempotent `PUT /api/v1/assets/resolve`, Flyway V5 external-identity
  uniqueness, duplicate-data upgrade policy, and real PostgreSQL concurrency
  coverage.
- Added a JDK HTTP multipart client with explicit `2xx`, authentication, asset
  re-resolution, functional `4xx`, `5xx`, timeout, DNS, and network policies.
- Added non-root systemd and Docker deployment options without mounting the
  Docker socket, plus deterministic fake-Trivy and optional real-Trivy demos.
- Updated CI to verify and build both backend and agent while keeping backend
  PostgreSQL integration tests mandatory.

## 0.2.0 - 2026-07-31

- Replaced synchronous Trivy processing with a persistent PostgreSQL ingestion
  queue and a configurable local background worker.
- Added secure local report storage with internally generated keys, temporary
  writes, atomic moves, traversal protection, and Docker volume persistence.
- Added bounded retries, deterministic backoff, dead-letter handling, explicit
  redrive, stale-job recovery, and attempt-aware completion guards.
- Added paginated ingestion-job APIs, Micrometer metrics, and PostgreSQL
  concurrency coverage for `SKIP LOCKED` and claim release.
- Added Flyway V3 constraints and indexes without modifying existing migrations.
- Added Flyway V4 with non-reusable UUID claim tokens, state-coherence checks,
  safe invalidation of existing processing claims, and an explicit 0.1.1 legacy
  scan policy.
- Added SHA-256 payload verification before parsing and conservative failure
  classification for deterministic, unknown, storage, and database errors.
- Added real flush rollback, ABA redrive, concurrent recovery, payload tamper,
  configuration validation, and V2-to-V4 Testcontainers coverage.
- Made PostgreSQL integration tests mandatory in the primary CI workflow.

## 0.1.1 - 2026-07-29

- Added explicit independent transactions for scan registration, completion,
  and failure recording.
- Added safe retries for failed scans, deterministic handling for concurrent
  uploads, and idempotent recovery of stale processing scans.
- Hardened Trivy structure validation, request error mapping, upload metadata,
  description limits, and stable pagination.
- Added stateless API-key authentication for `/api/v1/**` and localhost-only
  Docker port bindings.
- Added Flyway V2 for scan failure metadata and the processing-timeout index.
- Refreshed the Terraform AWS provider lock to an available version so
  non-deploying initialization and validation remain reproducible.
- Expanded unit and PostgreSQL integration coverage for retry, concurrency,
  rollback, security, client errors, and recovery.

## 0.1.0 - 2026-07-29

- Added the local-first Spring Boot API for assets, Trivy scans, findings, and
  dashboard summaries.
- Added PostgreSQL persistence, Flyway migrations, Docker Compose, tests,
  sample reports, and a repeatable demo.
- Fixed the Maven wrapper executable permission required by Linux CI runners.
- Added a non-deploying Terraform skeleton and architecture, security, and AWS
  roadmap documentation.
