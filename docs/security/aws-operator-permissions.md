# AWS operator permissions

VulnFlow deliberately uses a single-account IAM/STS design for human Terraform
access. AWS Organizations and IAM Identity Center must remain disabled so the
account stays on its current AWS Free Plan and retains its remaining credits.

```text
IAM user vacaro
  -> sts:AssumeRole
  -> VulnFlowTerraformOperator
  -> temporary STS credentials
  -> Terraform
```

The target account is `160172542031` and the workload region is `eu-west-1`.
The permanent user is only the one-time identity bootstrap principal and the
source for `AssumeRole`; it is not an approved identity for state or application
Terraform operations.

## Verified starting point

A read-only audit on 2026-08-01 established that:

- `arn:aws:iam::160172542031:user/vacaro` is the current caller;
- the user has no directly attached or inline policy, but group `admin` grants
  the AWS-managed `AdministratorAccess` policy;
- the user initially had no MFA device; a second read-only audit after operator
  enrollment verified `arn:aws:iam::160172542031:mfa/movil` on `vacaro`;
- `VulnFlowTerraformOperator` and VulnFlow customer-managed policies do not yet
  exist;
- the account is not a member of AWS Organizations; and
- there is no local `vulnflow-admin` profile yet.

No credential value was read, printed, changed, or written during that audit.

## One-time identity bootstrap

`infrastructure/identity-bootstrap` is a separate Terraform root. Its initial
plan creates exactly:

- one `VulnFlowTerraformOperator` role;
- three scoped customer-managed policies for state, application, and workload
  identity; and
- three attachments of those policies to the role.

The trust policy accepts only
`arn:aws:iam::160172542031:user/vacaro`. It contains no account-root,
cross-account, external, service, or wildcard principal. The role session is
limited to one hour.

The identity root fixes `mfa_serial` to the verified
`arn:aws:iam::160172542031:mfa/movil` device and the trust policy requires
`aws:MultiFactorAuthPresent=true`. A device rotation requires a new read-only
audit and reviewed code change; never reuse a root-device ARN or invent a
serial.

The permanent user must run the initial identity-bootstrap plan/apply because
the target role does not exist yet. Applying it is a STOP operation requiring
explicit authorization. This repository and its CI never apply it.

## Local AssumeRole profile

Only after the role exists, add this credential-free block to `~/.aws/config`:

```ini
[profile vulnflow-admin]
role_arn = arn:aws:iam::160172542031:role/VulnFlowTerraformOperator
source_profile = default
region = eu-west-1
role_session_name = vulnflow-terraform
mfa_serial = arn:aws:iam::160172542031:mfa/movil
```

Do not add access keys, secrets, or session tokens to the profile. Refreshing
means invoking an AWS CLI or Terraform command with the profile; the AWS CLI
uses the source profile to call STS and caches/renews temporary role sessions as
supported by its credential provider chain.

Before every state/application `plan`, migration, `apply`, or `destroy`, run:

```powershell
./scripts/aws/assert-temporary-identity.ps1 -Profile vulnflow-admin
```

The preflight requires account `160172542031`, region `eu-west-1`, and an STS
ARN matching
`arn:aws:sts::160172542031:assumed-role/VulnFlowTerraformOperator/<session>`.
It rejects the permanent IAM user and all unexpected roles.

## Effective operator policies

The single sources of truth are:

- `infrastructure/identity-bootstrap/policies/terraform-state-operator-policy.json`
- `infrastructure/identity-bootstrap/policies/application-operator-policy.json`

The state policy is limited to the dedicated bucket
`vulnflow-terraform-state-160172542031-eu-west-1`, its two reviewed state keys,
and their native `.tflock` objects. It grants no normal state-object deletion;
only lockfile deletion is allowed.

The application policy is limited to the reviewed demo report bucket, two SQS
queues, DynamoDB table, Lambda function, Lambda execution role, Lambda log
group, and optional DLQ alarm. The separate workload policy is limited to the
tagged VulnFlow Roles Anywhere resources. Their independent `iam:PassRole`
statements target only the processor role for `lambda.amazonaws.com` and the
backend role for `rolesanywhere.amazonaws.com`, respectively.

No policy grants `Action="*"`. The remaining `Resource="*"` statements are
isolated to APIs that cannot be scoped to a known ARN at authorization time:

- `sqs:ListQueues`;
- Lambda event-source-mapping creation and discovery calls; management after
  creation is restricted to the account/region event-source-mapping ARN type;
- `logs:DescribeLogGroups` and `cloudwatch:DescribeAlarms` discovery calls;
- Roles Anywhere create/list/tag-discovery APIs; creation requires the exact
  `Project=vulnflow`, `Environment=demo`, and `ManagedBy=Terraform` request
  tags, while later management is limited to those tagged profile/trust-anchor
  ARN types.

These exceptions contain only the listed actions and must remain separate from
resource-scoped management statements.

## Separation from VPS workload identity

The VPS never uses `vulnflow-admin` or the human operator role. Its prepared,
still-disabled chain is:

```text
VPS -> locally issued X.509 leaf -> IAM Roles Anywhere -> VulnFlow backend role
```

The CA private key remains offline and outside AWS; AWS Private CA is not used.
AWS documents `iam:PassRole` as a dependent permission for Roles Anywhere
profile creation. The operator grants that dependency only for
`arn:aws:iam::160172542031:role/vulnflow-demo-backend-role` and only to
`rolesanywhere.amazonaws.com`. See `docs/security/iam-roles-anywhere.md` for
the separate certificate ceremony and workload boundary.
