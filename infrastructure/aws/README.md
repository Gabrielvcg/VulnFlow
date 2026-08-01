# VulnFlow temporary AWS slice

This directory defines the first temporary AWS ingestion slice: private S3
report storage, encrypted SQS and DLQ queues, a Java 17 Lambda function,
least-privilege IAM, bounded CloudWatch Logs, and the SQS event source mapping.
It has no remote Terraform backend.

The configuration intentionally fails closed before deployment while
`result_store_provider_ready=false`. A reviewed implementation of
`LambdaProcessingResultStoreProvider` must be packaged before that safety gate
may be changed. See `docs/decisions/ADR-aws-result-storage.md`.

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
