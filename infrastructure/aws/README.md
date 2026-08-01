# VulnFlow temporary AWS slice

This directory defines the first temporary AWS ingestion slice: private S3
report storage, encrypted SQS and DLQ queues, an on-demand encrypted DynamoDB
result table, a Java 17 Lambda function, least-privilege IAM, bounded CloudWatch
Logs, an optional DLQ alarm, and the SQS event source mapping.
The Lambda uses unreserved account concurrency, while the SQS event source
mapping applies the bounded concurrency limit used by the temporary demo.
It has no remote Terraform backend.

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
