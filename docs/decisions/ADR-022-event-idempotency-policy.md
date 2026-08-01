# ADR-022: Idempotency is fenced by eventId and identity

Status: Accepted for 0.4.1.

## Decision

An `eventId` identifies exactly one logical processing attempt and is bound to `scanId`, `assetId`,
`contentHash`, scanner, and normalized finding count. Re-delivery of an already `COMPLETED` or `FAILED`
event succeeds as a duplicate. Reuse with different identity or content is a permanent conflict.

Incomplete `WRITING` events are resumable. Finding keys are deterministic and the final event/scan state
changes atomically. The Lambda checks finalized state before downloading S3, but correctness remains at
the conditional DynamoDB write boundary rather than relying on the optimization.

## Consequences

SQS and the publication outbox are allowed to deliver duplicates. Event identifiers must never be reused
for semantically different work. The normalized finding count is part of retry consistency; changing the
processor for an in-flight event may require a new event.
