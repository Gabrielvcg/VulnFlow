# VulnFlow Terraform state bootstrap

This independent Terraform root manages only the dedicated S3 bucket used for
Terraform state and its protections. It never manages application reports or
any resource under `infrastructure/aws`.

## Safety properties

- Region fixed to `eu-west-1`.
- Public access fully blocked.
- S3-managed encryption enabled.
- Bucket versioning enabled.
- Non-TLS requests denied by bucket policy.
- Bucket metadata access granted only to the exact MFA-protected
  `VulnFlowTerraformOperator` role so Terraform can refresh and import the
  dedicated bucket without account-wide bucket-list permissions.
- `force_destroy=false` and Terraform `prevent_destroy=true`.
- No credentials or backend secrets in source control.

The initial bootstrap necessarily uses local state because the backend bucket
does not exist yet. Do not commit that state. After the authorized bootstrap
apply, `backend.tf` migrates the bootstrap state to the new bucket under
`vulnflow/bootstrap/terraform.tfstate`; the application root uses
`vulnflow/demo/terraform.tfstate`. Both active backends use S3 native locking
through `use_lockfile=true` and contain no credentials.

## Plan-only validation

```bash
terraform fmt -check
terraform init -backend=false
terraform validate
terraform plan \
  -var='state_bucket_name=vulnflow-terraform-state-160172542031-eu-west-1' \
  -out=vulnflow-bootstrap.tfplan
terraform show vulnflow-bootstrap.tfplan
```

These commands do not create the bucket. See
`docs/operations/terraform-state-backend.md` for the future authorized apply
and state migration sequence.
