# ADR-007: PostgreSQL persistent ingestion queue

- Status: Accepted
- Date: 2026-07-31

## Context

VulnFlow needs asynchronous jobs that survive backend restarts, support two
worker instances, and remain local-first. Adding a separate broker would add an
operational dependency before the workload justifies it.

## Decision

Store ingestion jobs in PostgreSQL. Workers claim bounded batches with `FOR
UPDATE SKIP LOCKED`, update the job and scan in a short transaction, and release
all locks before loading or parsing a report.

One unique job is retained per scan and redrive reuses that row. PostgreSQL
checks constrain job status and attempt counters.

## Consequences

- Jobs survive application restarts without RabbitMQ, Kafka, Redis, or an
  in-memory queue.
- Database availability affects both API persistence and job scheduling.
- Queue growth and payload retention require future operational policies.
- The repository and claim boundary can later be replaced by an SQS adapter.
