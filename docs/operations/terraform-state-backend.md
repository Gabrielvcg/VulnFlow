# Terraform state backend

VulnFlow separates Terraform state from application data:

```text
infrastructure/bootstrap -> dedicated versioned state bucket
infrastructure/aws       -> reports bucket, queues, table, Lambda, logs, and IAM
```

The state bucket is `vulnflow-terraform-state-160172542031-eu-west-1`. The
application reports bucket remains
`vulnflow-demo-160172542031-eu-west-1-reports`. They must never be reused for
each other's purpose.

## Bootstrap sequence

The bucket cannot be its own backend before it exists. The first authorized
bootstrap apply therefore uses local state and must be performed from a clean,
access-controlled workstation. Keep the resulting state outside source control
and protect it until migration succeeds.

After the bucket exists:

1. Confirm Block Public Access, AES256 encryption, versioning, the TLS-only
   bucket policy, and `prevent_destroy` are effective.
2. Configure the bootstrap backend with bucket
   `vulnflow-terraform-state-160172542031-eu-west-1`, key
   `vulnflow/bootstrap/terraform.tfstate`, region `eu-west-1`, encryption, and
   `use_lockfile=true`.
3. Run `terraform init -migrate-state` from `infrastructure/bootstrap` and
   verify that both the state object and lockfile workflow work.
4. Copy `infrastructure/aws/backend.tf.example` to the active, reviewed
   `backend.tf` configuration.
5. Run `terraform init -migrate-state` from `infrastructure/aws`, using key
   `vulnflow/demo/terraform.tfstate` and `use_lockfile=true`.
6. Only after both migrations are verified should protected local state copies
   be retired.

Backend credentials are supplied by the AWS credential provider chain. They
must not appear in Terraform files, `.tfbackend` files, command history, or CI
variables. DynamoDB locking is intentionally not used.

No bootstrap or application apply is part of the preparation phase.
