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
- the user has no MFA device; the account's only listed MFA device belongs to
  the root user;
- `VulnFlowTerraformOperator` and VulnFlow customer-managed policies do not yet
  exist;
- the account is not a member of AWS Organizations; and
- there is no local `vulnflow-admin` profile yet.

No credential value was read, printed, changed, or written during that audit.

## One-time identity bootstrap

`infrastructure/identity-bootstrap` is a separate Terraform root. Its initial
plan creates exactly:

- one `VulnFlowTerraformOperator` role;
- two scoped customer-managed policies for state and application; and
- two attachments of those policies to the role.

The trust policy accepts only
`arn:aws:iam::160172542031:user/vacaro`. It contains no account-root,
cross-account, external, service, or wildcard principal. The role session is
limited to one hour.

The root's `mfa_serial` is `null` by default because MFA is not currently
configured on `vacaro`. After a real device is assigned to that user, supply its
verified ARN in a new reviewed identity-bootstrap plan. That adds the
`aws:MultiFactorAuthPresent=true` trust condition. Never reuse the root MFA ARN
or invent a serial.

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
```

If and only if the role trust has been updated with a real MFA device assigned
to `vacaro`, also add:

```ini
mfa_serial = arn:aws:iam::160172542031:mfa/<real-device-name>
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
group, and optional DLQ alarm. `iam:PassRole` targets only
`arn:aws:iam::160172542031:role/vulnflow-demo-processor-role` and requires
`iam:PassedToService=lambda.amazonaws.com`.

No policy grants `Action="*"`. The remaining `Resource="*"` statements are
isolated to APIs that cannot be scoped to a known ARN at authorization time:

- `sqs:ListQueues`;
- Lambda event-source-mapping creation and discovery calls; management after
  creation is restricted to the account/region event-source-mapping ARN type;
- `logs:DescribeLogGroups` and `cloudwatch:DescribeAlarms` discovery calls.

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
profile creation. Because the approved human operator may pass only the exact
Lambda execution role, the future Roles Anywhere apply needs a separate,
explicitly reviewed authorization for the exact backend role. The current
operator policy intentionally does not grant it and no Roles Anywhere resource
is included in the 14-resource application plan. See
`docs/security/iam-roles-anywhere.md` for the separate certificate ceremony and
workload boundary.
