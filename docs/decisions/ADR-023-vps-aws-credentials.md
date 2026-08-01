# ADR-023: Prefer short-lived workload credentials for the VPS

Status: Accepted for the prepared AWS deployment; activation still requires an authorized apply and certificate ceremony.

## Decision

Prefer AWS IAM Roles Anywhere for the existing non-AWS VPS when direct S3/SQS access is still required.
It exchanges a rotated X.509 workload identity for short-lived credentials and avoids committed or
long-lived access keys. Scope the resulting role to the exact report prefix and ingestion queue.

OIDC workload identity is preferable where the hosting platform supplies a trustworthy issuer, but a
generic VPS does not currently provide one. A proxy or presigned-upload API further reduces VPS AWS
permissions and is preferred if a later architecture can accept the extra service boundary. A dedicated
IAM user with long-lived keys is the least preferred fallback and would require external secret storage,
rotation, revocation, and leak monitoring.

## Consequences

Roles Anywhere introduces certificate authority, renewal, and helper-process operations. No access key,
certificate, trust anchor, role, or AWS account integration is created in this phase.

The backend image contains the checksum-pinned AWS signing helper and uses its
`credential-process` integration. The AWS Compose override mounts an X.509
certificate and private key read-only; the helper returns short-lived session
credentials directly to the AWS SDK provider chain. No AWS credentials file,
access key, metadata proxy, or credential-serving TCP endpoint is used.

The optional Terraform identity is disabled by default. When enabled, the role
trust is restricted by account, exact trust-anchor ARN, and the expected
`x509Subject/CN` value. The role and profile session policy permit only report
object operations, ingestion publication, and result reads.
