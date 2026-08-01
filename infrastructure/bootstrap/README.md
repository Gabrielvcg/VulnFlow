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
- `force_destroy=false` and Terraform `prevent_destroy=true`.
- No credentials or backend secrets in source control.

The initial bootstrap necessarily uses local state because the backend bucket
does not exist yet. Do not commit that state. After an explicitly authorized
bootstrap apply, migrate the bootstrap state to the new bucket under
`vulnflow/bootstrap/terraform.tfstate` and configure the application state at
`vulnflow/demo/terraform.tfstate`, both with S3 native locking enabled through
`use_lockfile=true`. `backend.tf.example` is the inactive, credential-free
bootstrap backend definition used for that migration.

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
