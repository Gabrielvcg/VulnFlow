# Temporary AWS deployment runbook

This runbook is documentation only. It was not executed for 0.4.0. Running it is a separate, explicitly authorized operation that needs reviewed credentials, current cost review, and a completed result-store provider.

## 1. Prechecks

- Confirm the branch/commit, clean tree, approved region/account, budget alert, operator identity, and scheduled destruction owner/time.
- Accept `ADR-aws-result-storage.md`, implement/test exactly one `LambdaProcessingResultStoreProvider`, and set `result_store_provider_ready=true` only after review.
- Choose a globally unique temporary bucket name and non-sensitive tags.
- Confirm payloads are synthetic and under the configured size limit.

## 2. Package

```text
./backend/mvnw -f pom.xml verify
```

Confirm `aws/lambda-processor/target/vulnflow-lambda-processor-0.4.0.jar` exists and calculate its base64 SHA-256 for `lambda_source_code_hash`.

## 3. Validate only

```text
terraform -chdir=infrastructure/aws fmt -check -recursive
terraform -chdir=infrastructure/aws init -backend=false -input=false
terraform -chdir=infrastructure/aws validate
```

These commands do not need AWS credentials. Do not confuse validation with deployment readiness.

## 4. Cost and security review

Complete `docs/aws-cost-model.md`, inspect IAM scope, timeouts, retention, concurrency, redrive, public-access blocking, and encryption. In a future authorized change, review a saved plan manually; never auto-apply from this CI.

## 5. Manual apply

Only an authorized operator may run apply after the safety gate and review. Record the exact commit, plan digest, approver, start time, and destruction deadline. This step was not run in 0.4.0.

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

Check the AWS resource inventory and billing/cost explorer later because deletion and billing visibility can lag. Record residual findings and remove them through an explicitly authorized follow-up.
