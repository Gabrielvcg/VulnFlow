# ADR-003: Target a serverless event-driven AWS architecture

- Status: Accepted for direction; implementation deferred
- Date: 2026-07-29

## Context

Scanner reports arrive intermittently and processing can be decoupled from
queries. A portfolio deployment should demonstrate messaging, failure handling,
observability, security, and cost control without idle servers.

## Decision

Target S3 for report objects, SQS plus DLQ for delivery, Java Lambda for
processing, DynamoDB for query storage, and API Gateway plus a query Lambda.
EventBridge, SNS, and CloudWatch will be added only for concrete scheduling,
alerting, and operational requirements.

## Consequences

The architecture can scale with events and has no permanently running compute.
It introduces at-least-once delivery, cold starts, IAM design, DynamoDB access
patterns, and observability work. Idempotency and bounded retries are mandatory.
No resources will be implemented until event and query contracts are validated
locally and costs are reviewed.

