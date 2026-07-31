# ADR-017: SQS versus the PostgreSQL local queue

Status: Accepted as coexistence, not replacement.

## Decision

Retain `ingestion_jobs`, `FOR UPDATE SKIP LOCKED`, `claimToken`, local backoff, stale recovery, redrive, and `DEAD_LETTER` for local/VPS mode. Use SQS delivery, receipt handles, visibility timeout, receive count, and an SQS DLQ for the future AWS mode.

No local queue class is copied into Lambda. Partial batch responses tell Lambda/SQS exactly which records failed.

## Consequences

The two paths provide at-least-once delivery through different mechanisms. `claimToken` is a durable database fencing generation; a receipt handle is valid for one delivery. Visibility expiry replaces abandoned-claim recovery operationally but is not a database state transition. Idempotent result storage is required in both cases.
