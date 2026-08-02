mock_provider "aws" {}

run "state_bucket_is_private_and_operator_is_exact" {
  command = plan

  variables {
    state_bucket_name = "vulnflow-terraform-state-160172542031-eu-west-1"
  }

  assert {
    condition = (
      aws_s3_bucket.terraform_state.force_destroy == false &&
      aws_s3_bucket_versioning.terraform_state.versioning_configuration[0].status == "Enabled" &&
      one(flatten([
        for rule in aws_s3_bucket_server_side_encryption_configuration.terraform_state.rule : [
          for encryption in rule.apply_server_side_encryption_by_default : encryption.sse_algorithm
        ]
      ])) == "AES256"
    )
    error_message = "The state bucket must prevent force deletion, enable versioning, and use AES256 encryption."
  }

  assert {
    condition = (
      aws_s3_bucket_public_access_block.terraform_state.block_public_acls &&
      aws_s3_bucket_public_access_block.terraform_state.block_public_policy &&
      aws_s3_bucket_public_access_block.terraform_state.ignore_public_acls &&
      aws_s3_bucket_public_access_block.terraform_state.restrict_public_buckets
    )
    error_message = "Every S3 Public Access Block setting must remain enabled."
  }

  assert {
    condition = (
      one([
        for statement in jsondecode(local.state_bucket_policy).Statement : statement
        if statement.Sid == "AllowExactTerraformOperatorBucketMetadata"
      ]).Principal.AWS == "arn:aws:iam::160172542031:role/VulnFlowTerraformOperator" &&
      toset(one([
        for statement in jsondecode(local.state_bucket_policy).Statement : statement
        if statement.Sid == "AllowExactTerraformOperatorBucketMetadata"
        ]).Action) == toset([
        "s3:GetBucketOwnershipControls",
        "s3:GetBucketPolicyStatus",
        "s3:GetLifecycleConfiguration",
        "s3:GetReplicationConfiguration",
        "s3:ListBucket"
      ])
    )
    error_message = "Only the exact MFA-protected Terraform operator may inspect bucket metadata."
  }

  assert {
    condition = (
      one([
        for statement in jsondecode(local.state_bucket_policy).Statement : statement
        if statement.Sid == "DenyInsecureTransport"
      ]).Effect == "Deny" &&
      one([
        for statement in jsondecode(local.state_bucket_policy).Statement : statement
        if statement.Sid == "DenyInsecureTransport"
      ]).Condition.Bool["aws:SecureTransport"] == "false"
    )
    error_message = "The bucket policy must deny every non-TLS request."
  }
}
