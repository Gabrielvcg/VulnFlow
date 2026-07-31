# ADR-015: Versioned ingestion event

Status: Accepted for 0.4.0.

## Decision

Use the immutable, strict JSON `IngestionEventV1` contract documented in `docs/contracts/ingestion-event-v1.md`. `eventId` is the result idempotency key; `payloadKey` is logical; report content and secrets never enter the message.

Unknown versions and incomplete messages fail the individual SQS record. Contract evolution creates a new version instead of changing V1 in place.

## Consequences

Producers and consumers can evolve independently with explicit compatibility. Strict unknown-field handling makes accidental producer drift visible, but additive evolution requires a new version rather than being silently tolerated.
