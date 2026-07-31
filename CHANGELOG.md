# Changelog

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
