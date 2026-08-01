mock_provider "aws" {}

override_module {
  target = module.lambda
  outputs = {
    function_name = "vulnflow-test-processor"
  }
}

variables {
  report_bucket_name      = "vulnflow-test-160172542031-eu-west-1-reports"
  result_store_provider   = "dynamodb"
  lambda_source_code_hash = "dGVzdC1zb3VyY2UtaGFzaA=="
}

run "default_plan_excludes_vps_identity" {
  command = plan

  assert {
    condition = (
      output.vps_roles_anywhere_trust_anchor_arn == null &&
      output.vps_roles_anywhere_profile_arn == null &&
      output.vps_roles_anywhere_role_arn == null
    )
    error_message = "The default application plan must not create a VPS workload identity."
  }
}

run "enabled_plan_exposes_scoped_vps_identity" {
  command = plan

  variables {
    enable_vps_roles_anywhere         = true
    roles_anywhere_ca_certificate_pem = <<-EOT
      -----BEGIN CERTIFICATE-----
      PLAN-ONLY-PUBLIC-CA-PLACEHOLDER
      -----END CERTIFICATE-----
    EOT
  }

  override_resource {
    target          = module.vps_identity[0].aws_rolesanywhere_trust_anchor.vps
    override_during = plan
    values = {
      arn = "arn:aws:rolesanywhere:eu-west-1:160172542031:trust-anchor/00000000-0000-0000-0000-000000000001"
    }
  }

  override_data {
    target = module.vps_identity[0].data.aws_iam_policy_document.backend_assume_role
    values = {
      json = jsonencode({
        Version = "2012-10-17"
        Statement = [{
          Effect    = "Allow"
          Principal = { Service = "rolesanywhere.amazonaws.com" }
          Action    = ["sts:AssumeRole", "sts:SetSourceIdentity", "sts:TagSession"]
        }]
      })
    }
  }

  override_data {
    target = module.vps_identity[0].data.aws_iam_policy_document.backend_access
    values = {
      json = jsonencode({
        Version = "2012-10-17"
        Statement = [{
          Effect   = "Allow"
          Action   = "sqs:SendMessage"
          Resource = "arn:aws:sqs:eu-west-1:160172542031:vulnflow-test-ingestion"
        }]
      })
    }
  }

  override_resource {
    target          = module.vps_identity[0].aws_rolesanywhere_profile.vps
    override_during = plan
    values = {
      arn = "arn:aws:rolesanywhere:eu-west-1:160172542031:profile/00000000-0000-0000-0000-000000000002"
    }
  }

  override_resource {
    target          = module.vps_identity[0].aws_iam_role.backend
    override_during = plan
    values = {
      arn = "arn:aws:iam::160172542031:role/vulnflow-demo-backend-role"
    }
  }

  assert {
    condition     = output.vps_roles_anywhere_trust_anchor_arn == "arn:aws:rolesanywhere:eu-west-1:160172542031:trust-anchor/00000000-0000-0000-0000-000000000001"
    error_message = "The enabled identity must expose the exact trust anchor ARN."
  }

  assert {
    condition     = output.vps_roles_anywhere_profile_arn == "arn:aws:rolesanywhere:eu-west-1:160172542031:profile/00000000-0000-0000-0000-000000000002"
    error_message = "The enabled identity must expose the exact profile ARN."
  }

  assert {
    condition     = output.vps_roles_anywhere_role_arn == "arn:aws:iam::160172542031:role/vulnflow-demo-backend-role"
    error_message = "The enabled identity must expose only the expected backend role ARN."
  }
}
