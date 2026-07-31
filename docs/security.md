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
- Vulnerability identifiers are rejected rather than silently truncated.
- Descriptions are limited to 8,000 characters by default.

The HTTP endpoint deliberately accepts syntactically or semantically invalid
JSON as durable work. Validation occurs asynchronously and such jobs terminate
directly in `DEAD_LETTER` without pointless retries.

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

Claim generation validation prevents an interrupted worker from completing a
newer attempt. `SKIP LOCKED`, unique scan/job constraints, and transactional
finding replacement are covered by PostgreSQL integration tests.

## Remaining risks

- A shared API key has broad access and no rate limiting.
- Public local metrics may disclose operational counts if host binding changes.
- Payload retention can exhaust disk space.
- The API still reads the bounded multipart file into memory.
- Local storage is not suitable for horizontally scaled hosts without a shared
  filesystem; S3 is the planned durable replacement.
- Dependency and container scanning should run in CI and release workflows.
