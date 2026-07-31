# ADR-004: Use a provisional API key for machine-to-machine access

- Status: Accepted
- Date: 2026-07-29

## Context

The local API previously allowed every caller to read and mutate VulnFlow data.
The current phase has automated clients but no human user or role model.

## Decision

Require `X-API-Key` for `/api/v1/**`, configure it through
`VULNFLOW_API_KEY`, and use stateless Spring Security. Keep health and Swagger
public on the localhost-only development binding. Do not add JWT, users, roles,
or browser sessions yet.

## Consequences

The API is no longer anonymous, but every valid key has the same authority.
Keys must not be logged or committed. A future human interface must replace or
augment this mechanism with OIDC/JWT and resource-level authorization.
