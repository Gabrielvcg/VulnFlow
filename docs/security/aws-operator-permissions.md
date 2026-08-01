# AWS operator permissions

The current local IAM user is an emergency/bootstrap identity, not the target
human access model. Human Terraform operations should use the AWS CLI profile
`vulnflow-admin` backed by IAM Identity Center and short-lived credentials.

Create two reviewed customer-managed policies or equivalent inline policies in
the Identity Center permission set. Do not use `AdministratorAccess` as the
default permission set.

## Identity Center manual setup

The workstation currently has only the long-lived `default` profile and no SSO
profile. Its IAM user cannot inspect Identity Center or Organizations, so an
AWS account/organization administrator must complete this decision in AWS
Console:

1. Open IAM Identity Center and confirm whether an organization instance is
   already enabled and in which region. Do not create a second instance.
2. If it is not enabled, choose the organization/standalone instance model,
   identity source, user or group ownership, and home region explicitly.
3. Create a `VulnFlowTerraformOperator` permission set using the scoped
   bootstrap and application permissions below.
4. Assign the operator user or group to account `160172542031` with that
   permission set.
5. Record the access portal start URL and SSO region, then configure and verify
   the local temporary profile without copying any credentials:

```bash
aws configure sso --profile vulnflow-admin
aws sso login --profile vulnflow-admin
aws sts get-caller-identity --profile vulnflow-admin
```

The verified ARN should be an assumed-role/SSO session rather than
`arn:aws:iam::160172542031:user/vacaro` before any apply is authorized.

## State bootstrap scope

Limit bucket-level actions to:

```text
arn:aws:s3:::vulnflow-terraform-state-160172542031-eu-west-1
```

Required management actions are `s3:CreateBucket`, `s3:DeleteBucket`,
`s3:GetBucketLocation`, bucket tagging, versioning, encryption, public-access
block and bucket-policy get/put/delete operations. The committed Terraform
guards still prevent accidental destruction.

After creation, backend access needs `s3:ListBucket` on the bucket and
`s3:GetObject`/`s3:PutObject` on these prefixes:

```text
vulnflow/bootstrap/terraform.tfstate*
vulnflow/demo/terraform.tfstate*
```

S3 native lockfiles additionally require `s3:DeleteObject` for the matching
`.tflock` objects. State-object deletion is not part of normal operation.

## Application infrastructure scope

Limit permissions to account `160172542031`, region `eu-west-1`, and these
names:

```text
S3 bucket:      vulnflow-demo-160172542031-eu-west-1-reports
SQS queues:     vulnflow-demo-ingestion, vulnflow-demo-dlq
DynamoDB table: vulnflow-demo-results and its indexes
Lambda:         vulnflow-demo-processor
IAM role:       vulnflow-demo-processor-role
Log group:      /aws/lambda/vulnflow-demo-processor
```

The Terraform operator needs create/read/update/delete and tagging operations
for those exact S3, SQS, DynamoDB, Lambda and CloudWatch Logs resources. IAM
permissions are limited to creating and managing the named Lambda role and its
inline policy. `iam:PassRole` must target only that role and require
`iam:PassedToService=lambda.amazonaws.com`.

Some discovery and create APIs, notably Lambda event-source-mapping discovery,
cannot be completely restricted to an already-known resource ARN because the
resource does not exist yet. Any unavoidable `Resource="*"` statement must
contain only the exact unsupported actions, be kept separate from resource-
scoped statements, and be reviewed through CloudTrail. It must never contain
service-wide full-access actions.

## VPS workload identity

The VPS is a separate workload and must not reuse human Terraform permissions.
The next phase may create this chain:

```text
VPS -> X.509 certificate -> IAM Roles Anywhere -> VulnFlowBackend role
```

That role will need only S3 `PutObject`/`GetObject` for `reports/*`, SQS
`SendMessage` for the ingestion queue, and DynamoDB read operations for the
VulnFlow result table and index. No trust anchor, certificate, role, profile or
access key is created in the current phase.
