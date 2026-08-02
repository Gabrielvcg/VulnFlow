# AWS runtime activation record — 2026-08-02

This record captures the controlled VulnFlow 0.4.8 activation in AWS account
`160172542031`, region `eu-west-1`. It contains identifiers and bounded results
only; no credentials, private keys, certificate secrets, API keys, or report
bodies are recorded.

## Operator and state

- Human session: `arn:aws:sts::160172542031:assumed-role/VulnFlowTerraformOperator/vulnflow-terraform`.
- MFA device: `arn:aws:iam::160172542031:mfa/movil`.
- The role trust names only IAM user `vacaro` and requires MFA.
- State bucket: `vulnflow-terraform-state-160172542031-eu-west-1`.
- Bootstrap key: `vulnflow/bootstrap/terraform.tfstate`.
- Application key: `vulnflow/demo/terraform.tfstate`.
- Both roots use S3 native `use_lockfile=true` locking. Lock contention and
  release were tested before the application apply.
- Bucket versioning is enabled, default encryption is AES256, all four Public
  Access Block settings are enabled, and the bucket policy denies non-TLS
  requests. The bucket and both state objects were read back successfully.

The identity bootstrap contains seven managed resources. The state bootstrap
contains the S3 bucket, policy, versioning, encryption, and Public Access Block.
The application remote state contains 18 managed resources. All final plans
reported no changes.

## Applied application boundary

The application state manages only these VulnFlow resources:

- private report bucket `vulnflow-demo-160172542031-eu-west-1-reports` with
  AES256 encryption, Public Access Block, and seven-day payload expiration;
- ingestion queue `vulnflow-demo-ingestion`, DLQ `vulnflow-demo-dlq`, redrive
  allow policy, and bounded Lambda event source mapping;
- on-demand encrypted DynamoDB table `vulnflow-demo-results` and its GSI;
- Java 17 Lambda `vulnflow-demo-processor`, exact execution role/policy, and
  seven-day CloudWatch log group;
- IAM Roles Anywhere trust anchor, profile, backend role, and inline
  least-privilege policy.

No apply contained a destroy action. A partially interrupted initial apply was
reconciled by reading remote state and the actual VulnFlow resources before a
new plan was generated. No resource was improvised outside Terraform.

## IAM Roles Anywhere and VPS

- Trust anchor ARN: `arn:aws:rolesanywhere:eu-west-1:160172542031:trust-anchor/f2313f8b-9e4b-4cce-914f-1938edc9274f`.
- Profile ARN: `arn:aws:rolesanywhere:eu-west-1:160172542031:profile/0cdf9cc8-dcf8-4366-969f-6fb9fbec3952`.
- Workload role: `arn:aws:iam::160172542031:role/vulnflow-demo-backend-role`.
- Session duration: 15 minutes. Effective permissions are limited to report
  objects, ingestion send, and DynamoDB result read/query.
- The leaf key was generated on the VPS and never left it. The encrypted CA key
  is held offline outside the repository; AWS Private CA was not used.
- The leaf certificate uses CN `vulnflow-backend.vacaro.es`, URI SAN
  `spiffe://vulnflow/prod/backend`, client authentication, and a validity ending
  on 2026-08-31.
- A real credential-process call returned
  `arn:aws:sts::160172542031:assumed-role/vulnflow-demo-backend-role/4b74c0fb515a80ad5c31d788d65d84dc42d7fa4d`.
  No permanent access-key environment variable or credentials file exists.

The VPS runs `prod,aws`; `VULNFLOW_WORKER_ENABLED=false`. Backend and PostgreSQL
are healthy, the agent remains active, and the `vulnflow-agent-data`,
`vulnflow-backend-reports`, and `vulnflow-postgres-data` volumes remain intact.

## Real processing evidence

The normal agent outbox published two real reports through
Agent → VPS API → S3 → SQS → Lambda → DynamoDB:

- Alpine scan `52d81619-2dc2-4b83-8849-67782380ac5a`, event
  `41b941d6-8e08-40a0-8789-8cbc08eae5fc`: `COMPLETED`, zero findings.
- Debian scan `e16eb83d-9ac1-4d68-ad72-987201434842`, event
  `847d5d59-4040-4068-ae1c-e0af8a081084`: `COMPLETED`, 190 findings returned
  over two public API pages.

S3 metadata checksums matched the agent outbox, the local ingestion job count
for these scans remained zero, both SQS queues drained, and Lambda reported two
successful invocations. Replaying the accepted event preserved the terminal
result and did not create queue residue.

An isolated retry test used event
`e27d39f1-7c01-4813-ba69-fc5fa48041e3`. An intentionally unavailable S3
storage class caused exactly three transient Lambda failures at the configured
visibility interval and then DLQ delivery. After the object was restored to a
readable storage class, a controlled DLQ redrive completed the result with zero
findings. The source queue and DLQ both ended with zero visible and zero
in-flight messages. Temporary human data-plane verification permissions were
then removed through a reviewed `0 add / 1 change / 0 destroy` Terraform plan.
The one-time permission used to let Roles Anywhere create its AWS-managed
service-linked role was also removed after the role existed.

## Rollback, cost, and residuals

Rollback restores `runtime/.env.prod.pre-aws` and redeploys the current or
previous immutable release through `deploy/deploy.sh`. This returns the backend
to `prod` and the local worker without deleting PostgreSQL, report data, or any
Docker volume. Terraform destruction is a separate operation and was not run.

The active services are serverless or S3 pay-per-use resources. The expected
cost is low at demo traffic; retained DynamoDB rows have no TTL, CloudWatch and
report payloads use seven-day retention, and the protected remote-state bucket
is intentionally persistent. The isolated GLACIER object was only a few hundred
bytes, so its minimum-duration charge is negligible. Residuals are the active
Terraform-managed resources, the AWS-created Roles Anywhere service-linked
role, offline CA material, the short-lived VPS leaf key/certificate, test
DynamoDB rows, and report objects pending lifecycle expiration.

AWS Organizations returned `AWSOrganizationsNotInUseException` after the
activation. IAM Identity Center remained unused, no access keys were created,
and no infrastructure outside the VulnFlow resource boundary was modified.
