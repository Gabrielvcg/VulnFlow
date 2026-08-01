# VulnFlow human identity bootstrap

This independent Terraform root creates only the short-lived human operator
boundary used by later VulnFlow Terraform work:

```text
IAM user vacaro -> sts:AssumeRole -> VulnFlowTerraformOperator -> temporary STS
```

It creates one role, two scoped customer-managed policies, and two policy
attachments. The trust policy names only
`arn:aws:iam::160172542031:user/vacaro`; it has no account-root, wildcard,
cross-account, or external principal. `mfa_serial` defaults to `null` because
the user currently has no assigned MFA device. Set it only to a verified real
device ARN in a newly reviewed plan.

This root intentionally uses the existing bootstrap IAM user once. It does not
manage access keys, the user, its groups, AWS Organizations, IAM Identity
Center, the state bucket, or application infrastructure. It also does not grant
the Roles Anywhere profile's required `iam:PassRole`; that future workload
identity needs a separate explicit authorization because this role may pass
only the Lambda execution role.

## Plan-only validation

```powershell
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
terraform test
terraform plan -out=vulnflow-identity-bootstrap.tfplan
terraform show vulnflow-identity-bootstrap.tfplan
```

These commands do not create the role or policies. An `apply` requires explicit
authorization. After an authorized apply, configure the credential-free
`vulnflow-admin` AssumeRole profile described in
`docs/security/aws-operator-permissions.md` and stop using the IAM user directly
for state or application Terraform work.
