# VulnFlow AWS infrastructure

This directory is a validation-only Terraform skeleton for VulnFlow's future
serverless AWS architecture. It currently defines provider constraints,
variables, naming, tags, and a configuration summary output. It defines **zero
AWS resources** and has no remote backend.

## Validate safely

No AWS credentials are required for formatting, initialization, or validation:

```bash
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
```

From the repository root, the equivalent containerized commands are:

```bash
make terraform-format
make terraform-validate
```

`terraform init` downloads the provider plugin but does not create AWS
resources. Do not run `terraform apply` until every future module, IAM policy,
retention setting, budget alarm, and estimated cost has been reviewed.

## Future modules

The directories under `modules/` only document intended boundaries. Resource
definitions will be introduced incrementally after the local ingestion
contract is stable.

The intended lifecycle is:

```text
Permanent local development
+
Temporary and reproducible AWS deployment
+
terraform destroy after demonstrations
```

