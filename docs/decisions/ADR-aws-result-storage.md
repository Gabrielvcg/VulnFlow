# ADR: AWS result storage

Status: Superseded by ADR-019 in 0.4.1. This file records the 0.4.0 evaluation.

## Context

The Lambda processor must atomically make normalized findings and scan completion visible while treating repeated `eventId` values as successful duplicates. VulnFlow 0.4.0 defines `ProcessingResultStore<IngestionEventV1>` but does not ship a production Lambda provider. The Lambda no-argument wiring fails closed unless exactly one provider is packaged, and Terraform blocks apply while `result_store_provider_ready` is false.

## Alternative A: Lambda to existing PostgreSQL

- Preserves the existing API query schema and transactional replacement of findings plus scan completion.
- Requires an idempotency table/unique `event_id`, one short transaction, bounded connection pool, secret retrieval, TLS, and careful concurrency.
- Direct database access from Lambda can create connection spikes. A private database may require VPC networking; adding NAT solely for this path would add avoidable idle cost. RDS Proxy can reduce connection pressure but adds cost and another resource.
- Migration is smaller because API reads remain unchanged and consistency can be transactional.

## Alternative B: Lambda to DynamoDB

- Removes relational connection pressure and fits event-id conditional writes, elastic request-driven capacity, and per-item idempotency.
- Changes the findings/query model, pagination, aggregate dashboard strategy, indexing, and API persistence adapter.
- Atomicity is limited by DynamoDB transaction item/size limits; large scans need chunking and a completion marker. Duplicate and partial-chunk recovery become explicit protocol concerns.
- Migration and dual-read/cutover complexity are materially higher.

## Recommendation

Use PostgreSQL for the first AWS slice, provided the database is already safely reachable without creating a NAT gateway, and add a unique processed-event record in the same completion transaction. Cap Lambda concurrency and connections. If private connectivity would require expensive permanent networking solely for this demo, pause instead of deploying.

DynamoDB remains a later architectural project after defining API access patterns and chunked scan consistency. No DynamoDB adapter or Terraform resource is included in 0.4.0.
