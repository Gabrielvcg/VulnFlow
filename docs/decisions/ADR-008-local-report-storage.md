# ADR-008: Local report storage

- Status: Accepted
- Date: 2026-07-31

## Context

Asynchronous processing requires the uploaded report to outlive the HTTP
request. Storing complete JSON documents in the job row would couple queue
queries to large payloads and diverge from the planned object-storage model.

## Decision

Use the `ReportStorage` interface and a `LocalFileReportStorage` adapter. Keys
are generated internally, resolved under one configured directory, and never
derived from the client filename. Writes use a temporary file followed by an
atomic move when supported. Docker mounts the directory as a named volume.

Completed payloads are retained in 0.2.0. No API returns physical paths or
payload content.

## Consequences

- Jobs and payloads survive backend container replacement when the volume is
  retained.
- A database/filesystem distributed transaction is unavailable; registration
  writes the payload inside the short database transaction and registers
  rollback cleanup.
- Backup, capacity, cleanup, encryption-at-rest, and retention remain local
  operator responsibilities.
- A later adapter can map the same contract to S3 object keys.
