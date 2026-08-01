# Spring API security findings

Scope: VulnFlow 0.4.1 AWS execution path on `codex/0.4.1-aws-execution-path`.

## Summary

No open critical, high, or medium vulnerability was demonstrated in the reviewed change. One low-risk
configuration defect was demonstrated and fixed. The remaining entries are accepted local-phase risks or
future deployment prerequisites, not claims that an AWS deployment is ready.

## VF-AWS-001 — Ambiguous S3 prefix accepted during property binding

- Severity: Low
- Status: Resolved in this branch
- Evidence: `backend/src/main/java/com/vulnflow/config/AwsIngestionProperties.java:29` and
  `backend/src/test/java/com/vulnflow/config/AwsAdapterConfigurationTest.java`
- Impact: a prefix such as `reports//incoming` could allow the S3 upload and then make
  `IngestionEventV1` reject the generated key, causing avoidable compensation and possible orphaning if
  deletion also failed.
- Resolution: configuration now rejects repeated separators, traversal-like components, backslashes,
  leading slash, IP-shaped bucket names, adjacent dots, and invalid dot/hyphen boundaries before clients
  are usable.

## VF-AWS-002 — Shared API key and no rate limiting

- Severity: Low for the current loopback/VPS machine-to-machine phase; would be higher if exposed broadly
- Status: Accepted existing limitation
- Evidence: `backend/src/main/java/com/vulnflow/security/SecurityConfiguration.java:35-46`
- Impact: one leaked key grants access to every `/api/v1/**` operation and uploads can consume bounded but
  material memory/storage/queue capacity.
- Recommendation: use per-client OIDC/JWT authorization and request/upload rate limits before a public or
  multi-tenant endpoint.

## VF-AWS-003 — No distributed S3/PostgreSQL/SQS transaction

- Severity: Informational architectural risk
- Status: Mitigated and documented
- Evidence: `AwsScanSubmissionService.registerReceived`, `AwsScanRegistrationTransaction.register`, and
  `AwsOutboxPublisher.publish`
- Impact: process death after S3 upload can leave an orphan; SQS success followed by PostgreSQL failure
  causes a duplicate after stale recovery.
- Controls: best-effort upload compensation, S3 lifecycle, durable outbox, short `SKIP LOCKED` claims,
  UUID claim-token fencing, and DynamoDB `eventId` idempotency.

## VF-AWS-004 — Future VPS workload credentials are not provisioned

- Severity: Deployment prerequisite, not a code vulnerability
- Status: Intentionally not configured
- Evidence: `docs/decisions/ADR-023-vps-aws-credentials.md`
- Impact: the AWS backend profile cannot be operated securely until a short-lived credential source and
  table/queue/prefix-scoped backend role are approved.
- Recommendation: prefer IAM Roles Anywhere for the current generic VPS, or platform OIDC/proxy when a
  trustworthy issuer or narrower service boundary is available. Do not create long-lived access keys.

## Verified controls

- `/api/v1/**` remains authenticated and the AWS result endpoints return DTO/record projections, not JPA entities.
- Local mode does not create AWS clients; `aws` profile selection and incomplete-config failure are tested.
- S3 object keys are internally generated; clients cannot submit a payload key.
- SDK clients have connect/socket/API timeouts and standard retries (`AwsAdapterConfiguration:35,48,61`).
- S3 public access is blocked; S3, SQS/DLQ, and DynamoDB encryption are declared.
- Lambda IAM is scoped to the exact bucket prefix, source queue, result table/index, and log group.
- Events and logs exclude credentials and report content; persisted/public errors are fixed and bounded.
- Lambda returns only transient records in `batchItemFailures`; permanent failures are safely persisted.
- No AWS credential patterns, local AWS emulator use, or custom SDK endpoints were found.

## Scanner status

- Inventory scanner: completed; output in `security-inventory.json`.
- Manual endpoint/auth/upload/outbox/Lambda/IAM review: completed.
- Credential-pattern CI check: added and run locally.
- Live DAST and real AWS IAM simulation: not run because this phase explicitly forbids deployment and AWS access.
