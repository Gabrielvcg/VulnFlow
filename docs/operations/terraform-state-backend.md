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

The bucket could not be its own backend before it existed. The first authorized
bootstrap apply therefore used local state from an access-controlled
workstation. The migration is complete and no local state is used by either
active root.

After the bucket exists:

1. Confirm Block Public Access, AES256 encryption, versioning, the TLS-only
   bucket policy, and `prevent_destroy` are effective.
2. Use the reviewed `infrastructure/bootstrap/backend.tf`. It fixes the bucket,
   `vulnflow/bootstrap/terraform.tfstate`
   key, `eu-west-1` region, encryption, and `use_lockfile=true` without
   credentials.
3. Run `terraform init -migrate-state` from `infrastructure/bootstrap` and
   verify that both the state object and lockfile workflow work.
4. Use the active, reviewed `infrastructure/aws/backend.tf` configuration.
5. Run `terraform init -migrate-state` from `infrastructure/aws`, using key
   `vulnflow/demo/terraform.tfstate` and `use_lockfile=true`.
6. Only after both migrations are verified should protected local state copies
   be retired.

Backend credentials are supplied by the AWS credential provider chain. They
must not appear in Terraform files, `.tfbackend` files, command history, or CI
variables. DynamoDB locking is intentionally not used.

The one-time `infrastructure/identity-bootstrap` root is planned with the
existing bootstrap user because the operator role does not exist yet. It must
be explicitly authorized and applied before configuring `vulnflow-admin`.

Before every subsequent state or application `plan`, migration, apply, or
destroy, run:

```powershell
./scripts/aws/assert-temporary-identity.ps1 -Profile vulnflow-admin
```

The profile uses `source_profile=default` and the exact role ARN
`arn:aws:iam::160172542031:role/VulnFlowTerraformOperator`. The preflight
rejects IAM users, unexpected accounts, regions, and roles. It only prints the
safe caller identity; it does not read or write credential values. See
`docs/security/aws-operator-permissions.md` for the full profile and optional
real-MFA configuration.

The 2026-08-02 activation completed both migrations, proved lock contention and
release, read both encrypted state objects, and ended with no-change bootstrap
and application plans. Future state operations still require the exact
temporary operator identity and the same review gate.
