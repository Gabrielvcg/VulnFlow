# Security

## Current trust boundary

VulnFlow 0.1.1 remains local-first. Docker Compose binds the backend and
PostgreSQL to `127.0.0.1`. The API must not be exposed directly to the internet.

Every `/api/v1/**` endpoint requires `X-API-Key`. The configured value comes
from `VULNFLOW_API_KEY`, is compared without logging, and creates a stateless
machine-to-machine authentication. Missing or wrong credentials return the
common `401 INVALID_API_KEY` response.

`/actuator/health`, `/v3/api-docs`, and Swagger UI remain public for local
development. Swagger must be disabled or protected before a non-local
deployment.

This API key is provisional. It has no user identity, roles, authorization,
ownership, tenant isolation, expiry, or built-in rotation. A future human UI
must use OIDC or JWT and enforce resource-level authorization.

## Implemented controls

- API key required for every application endpoint.
- Stateless security; HTTP Basic, form login, sessions, logout, and generated
  development users are disabled.
- CSRF is disabled because authentication is a stateless custom header and no
  browser session/cookie is used.
- Localhost-only Docker port publication.
- No committed real secrets; `.env` remains ignored.
- Upload and request size limits.
- Semantic Trivy structure validation and bounded descriptions.
- Critical identifiers are rejected when oversized rather than truncated.
- Basename-only bounded filenames are metadata, never write paths.
- Stable structured errors without client stack traces.
- Correlation IDs are restricted before entering MDC.
- Report bodies, API keys, authorization headers, and descriptions are not
  logged.
- Flyway owns schema changes and Hibernate uses `ddl-auto=validate`.
- Scan registration, completion, and failure use independent explicit
  transactions.

## Remaining risks

- No rate limiting or TLS termination.
- API keys identify no individual client and cannot express authorization.
- Uploaded JSON is fully materialized in memory.
- Swagger is public on the local binding.
- Dependency, container, and secret scanning are not yet CI gates.
- Asset and finding identifiers are not ownership-scoped.

## Before any AWS deployment

The cloud phase must add an explicit identity and authorization model,
least-privilege IAM, managed secrets, TLS, throttling, encrypted private storage
and queues, bounded logs, alarms, scanning gates, incident response, and teardown
verification. Every AWS design must be threat-modeled and cost-reviewed before
any `terraform apply`.
