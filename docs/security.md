# Security

## Current trust boundary

The phase-one API is intended only for a developer workstation or an isolated
local network. Authentication and authorization are deliberately deferred until
the basic API contract is stable. The API must not be exposed directly to the
internet or an untrusted network.

## Implemented controls

- Multipart uploads are required to be non-empty and JSON media types.
- Both the servlet and application service enforce a configurable maximum size.
- Trivy JSON is parsed as data with Jackson; no polymorphic type activation is
  enabled.
- Required vulnerability fields are validated before persistence.
- SHA-256 content hashes support idempotency without logging report content.
- Original filenames are reduced to their basename and bounded before storage.
- Bean Validation protects asset and finding-status requests.
- Centralized errors return stable codes and never expose stack traces.
- Correlation IDs are generated or accepted only from a restricted character
  set and are returned in `X-Correlation-ID`.
- Logs are structured. Application log messages are operational Spanish and do
  not include secrets, personal data, authorization headers, or full reports.
- Database credentials enter through environment variables. `.env`, Terraform
  state, variable files, and build output are ignored by Git.
- Actuator exposes only health and info; health details are not returned.
- Hibernate schema mutation is disabled with `ddl-auto=validate`.

## Known risks

- No authentication, authorization, rate limiting, or TLS termination exists.
- Asset and finding identifiers are not ownership-scoped.
- Uploaded JSON is read into memory after the configured size gate.
- Vulnerability descriptions are accepted as scanner-provided text and must be
  output-encoded by any future UI.
- Local development credentials are intentionally weak examples.
- Dependency and container scanning are not yet part of CI.

These are acceptable only within the current local trust boundary.

## Before any AWS deployment

The cloud phase must add:

- an explicit identity model and endpoint authorization rules;
- least-privilege IAM roles separated by ingestion and query capability;
- private S3 buckets with public access blocked and server-side encryption;
- encrypted SQS queues and a bounded redrive policy;
- API throttling, request limits, access logs, and TLS;
- secrets in a managed service rather than Terraform variables or state;
- DynamoDB authorization and access patterns resistant to BOLA/IDOR;
- CloudWatch log retention, alarms, and sensitive-data review;
- artifact signing, dependency scanning, IaC scanning, and an SBOM for VulnFlow;
- documented incident response and teardown verification.

Every AWS design must be threat-modeled and cost-reviewed before `apply`.

