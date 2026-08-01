# VulnFlow temporary AWS slice

This directory defines the first temporary AWS ingestion slice: private S3
report storage, encrypted SQS and DLQ queues, an on-demand encrypted DynamoDB
result table, a Java 17 Lambda function, least-privilege IAM, bounded CloudWatch
Logs, an optional DLQ alarm, and the SQS event source mapping.
The Lambda uses unreserved account concurrency, while the SQS event source
mapping applies the bounded concurrency limit used by the temporary demo.
It has no active remote Terraform backend until the dedicated bucket in
`infrastructure/bootstrap` has been created by a separately authorized apply.
`backend.tf.example` contains the reviewed S3 backend shape with native
`use_lockfile=true` locking and no credentials.

The optional `modules/rolesanywhere` path prepares the non-AWS VPS workload
identity. It is disabled by default, so the initial application plan remains at
14 resources. Enabling it adds a trust anchor, profile, backend IAM role, and
inline role policy after a separate certificate ceremony and plan review. The
role trust is restricted by account, exact trust anchor, and certificate CN;
the role and session policy are both restricted to the VulnFlow report prefix,
ingestion queue, and read-only result access.

The configuration intentionally fails closed before deployment while
`result_store_provider="none"`. After reviewing the packaged DynamoDB adapter,
an authorized operator must explicitly set it to `"dynamodb"`. See ADR-019 and
the temporary deployment runbook.

## Offline-safe validation

Formatting and static validation need no AWS credentials and do not create
resources:

```bash
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
```

From the repository root, `make terraform-format` and
`make terraform-validate` run the same checks in a Terraform container.

Do not run plan, apply, or destroy as part of CI. Any future temporary apply
requires the manual cost/security review and complete create-test-destroy flow
in `docs/aws-temporary-deployment-runbook.md`.
