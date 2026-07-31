# ADR-009: At-least-once ingestion processing

- Status: Accepted
- Date: 2026-07-31

## Context

A worker may stop after claiming a job or after committing database changes.
The system must recover without holding a database lock while parsing and
without allowing an obsolete worker to overwrite a newer attempt.

## Decision

Use at-least-once delivery semantics with idempotent finalization:

- Claiming increments `attemptCount`, records `lockedAt`, and creates a random
  UUID `claimToken` transactionally.
- The detached claim includes both the retry count and claim token.
- Completion locks the job and scan and accepts only the current processing
  token. Failure handling enforces the same token.
- Existing findings are replaced in the same transaction that marks the scan
  and job `COMPLETED`.
- Stale processing leases move to `RETRY_WAIT` or `DEAD_LETTER` through `SKIP
  LOCKED` recovery.
- Functional report errors dead-letter immediately. Transient failures use a
  deterministic bounded backoff.
- Recovery and redrive invalidate the active token. Every later claim uses a new
  UUID, including when redrive resets `attemptCount` and creates an ABA-shaped
  retry count.

## Consequences

Duplicate delivery is possible, but duplicate committed findings are not.
Workers do not sleep between retries. Manual redrive resets the attempt counter
and reuses the same job and payload, but never its claim token. `attemptCount`
controls retry budget and backoff only; `claimToken` is the fencing generation.

A future SQS worker will use receipt handles and visibility timeout renewal for
delivery ownership. It will not reproduce PostgreSQL `SKIP LOCKED` as an SQS
protocol.
