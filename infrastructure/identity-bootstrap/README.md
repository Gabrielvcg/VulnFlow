# VulnFlow human identity bootstrap

This independent Terraform root creates only the short-lived human operator
boundary used by later VulnFlow Terraform work:

```text
IAM user vacaro -> sts:AssumeRole -> VulnFlowTerraformOperator -> temporary STS
```

It creates one role, three scoped customer-managed policies, and three policy
attachments. State, application, and workload-identity permissions are split
to stay below the AWS managed-policy size quota. The trust policy names only
`arn:aws:iam::160172542031:user/vacaro`; it has no account-root, wildcard,
cross-account, or external principal. The trust policy requires MFA and the
reviewed device assigned to `vacaro` is fixed as
`arn:aws:iam::160172542031:mfa/movil`.

This root intentionally uses the existing bootstrap IAM user once. It does not
manage access keys, the user, its groups, AWS Organizations, IAM Identity
Center, the state bucket, or application infrastructure. The application
policy can pass only the exact Lambda execution role to Lambda and the exact
backend role to IAM Roles Anywhere.

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
