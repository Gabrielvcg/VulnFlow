# Temporary AWS deployment runbook

This runbook was exercised for the controlled VulnFlow 0.4.8 activation on
2026-08-02. Remote state, temporary human authentication, the application
slice, and the VPS workload identity are active. Every future `terraform apply`
remains a separate authorized operation requiring a reviewed plan, short-lived
credentials, and a current cost/security review. See the runtime activation
record for the exact evidence and retained resources.

## 1. Prechecks

- Confirm the branch/commit, clean tree, approved region/account, budget alert, operator identity, and scheduled destruction owner/time.
- Read the regional Lambda account concurrency limit. Keep the function unreserved for low-quota accounts and bound the SQS event source with `sqs_maximum_concurrency`.
- Review ADR-019 through ADR-023 and set `result_store_provider="dynamodb"` only in an authorized reviewed input.
- Choose a globally unique temporary bucket name and non-sensitive tags.
- Confirm payloads are synthetic and under the configured size limit.

## 2. Package

```text
./backend/mvnw -f pom.xml verify
```

Confirm `aws/lambda-processor/target/vulnflow-lambda-processor-0.4.8.jar` exists and calculate its base64 SHA-256 for `lambda_source_code_hash`.

## 3. Validate only

```text
terraform -chdir=infrastructure/aws fmt -check -recursive
terraform -chdir=infrastructure/aws init -backend=false -input=false
terraform -chdir=infrastructure/aws validate
terraform -chdir=infrastructure/identity-bootstrap fmt -check -recursive
terraform -chdir=infrastructure/identity-bootstrap init -backend=false -input=false
terraform -chdir=infrastructure/identity-bootstrap validate
terraform -chdir=infrastructure/identity-bootstrap test
```

These commands do not need AWS credentials. Do not confuse validation with deployment readiness.

## 4. Cost and security review

Complete `docs/aws-cost-model.md`, inspect IAM scope, timeouts, retention, concurrency, redrive, public-access blocking, and encryption. In a future authorized change, review a saved plan manually; never auto-apply from this CI.

## 5. Manual apply

The existing `vacaro` bootstrap user may apply only the independently reviewed
identity plan. Configure `vulnflow-admin`, pass the temporary identity
preflight, and use only that role session for state/application work. Only an
authorized operator may run apply after the safety gate and review. Record the
exact commit, plan digest, operator, start time, and retention or destruction
decision. The 2026-08-02 activation followed this sequence and did not run any
destroy operation.

## 6. End-to-end evidence

Upload one synthetic report, publish one V1 event, verify findings/scan completion through the selected result store/API, replay the same `eventId` to prove idempotency, and submit one controlled bad message to prove partial failure/DLQ behavior. Record identifiers and bounded metadata, never report bodies or credentials.

## 7. Destroy and residual verification

Run destroy manually from the same reviewed configuration, then independently verify that all of these are gone:

- report S3 bucket, every object, and incomplete multipart upload;
- ingestion SQS queue and DLQ, including retained messages;
- Lambda function and event source mapping;
- Lambda IAM role and inline policy;
- CloudWatch log group and streams;
- any temporary local state/artifact containing identifiers;
- any result-store demo records or secrets created outside this Terraform scope.
- DynamoDB result table, GSI, PITR recovery data, and optional DLQ alarm;

Check the AWS resource inventory and billing/cost explorer later because deletion and billing visibility can lag. Record residual findings and remove them through an explicitly authorized follow-up.

## SQS DLQ inspection and manual redrive

Treat DLQ depth as an AWS operational signal, not the PostgreSQL outbox state. Inspect only message IDs,
receive counts, timestamps, event IDs, and safe error results; never log or export report bodies. Before a
manual redrive, confirm the S3 object still exists, the event contract is supported, the permanent cause
was corrected, and DynamoDB has no conflicting identity. Redrive from the DLQ to the source queue only
through an explicitly authorized AWS operation. Duplicate delivery is expected and fenced by `eventId`.
Stop if DLQ depth grows again, and record affected identifiers and operator actions.
