# ADR-002: Keep development local-first

- Status: Accepted
- Date: 2026-07-29

## Context

The project must demonstrate cloud architecture without requiring permanent
infrastructure, credentials, or monthly cost during normal development.

## Decision

PostgreSQL and the backend run with Docker Compose. Unit and integration tests
need no AWS account. Terraform is formatted and validated without credentials,
and phase one defines no cloud resources.

## Consequences

Contributors can reproduce the platform locally and CI can verify it cheaply.
AWS-specific behavior must later have explicit adapters and acceptance tests.
Temporary cloud demonstrations require a plan review, a teardown owner, and
`terraform destroy`.

