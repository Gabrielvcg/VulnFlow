# ADR-009: At-least-once ingestion processing

- Status: Accepted
- Date: 2026-07-31

## Context

A worker may stop after claiming a job or after committing database changes.
The system must recover without holding a database lock while parsing and
without allowing an obsolete worker to overwrite a newer attempt.

## Decision

Use at-least-once delivery semantics with idempotent finalization:

- Claiming increments `attemptCount` and records `lockedAt` transactionally.
- The detached claim includes its attempt number.
- Completion locks the job and scan and accepts only the current processing
  attempt.
- Existing findings are replaced in the same transaction that marks the scan
  and job `COMPLETED`.
- Stale processing leases move to `RETRY_WAIT` or `DEAD_LETTER` through `SKIP
  LOCKED` recovery.
- Functional report errors dead-letter immediately. Transient failures use a
  deterministic bounded backoff.

## Consequences

Duplicate delivery is possible, but duplicate committed findings are not.
Workers do not sleep between retries. Manual redrive resets the attempt counter
and reuses the same job and payload.
