locals {
  common_tags = merge(
    {
      Project     = "vulnflow"
      Environment = "shared"
      Component   = "TerraformIdentity"
      ManagedBy   = "Terraform"
    },
    var.additional_tags
  )

  policy_files = {
    state = "terraform-state-operator-policy.json"
    app   = "application-operator-policy.json"
  }

  operator_trust_statement = merge(
    {
      Sid       = "AllowExactBootstrapUser"
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { AWS = var.trusted_user_arn }
    },
    var.mfa_serial == null ? {} : {
      Condition = {
        Bool = { "aws:MultiFactorAuthPresent" = "true" }
      }
    }
  )

  operator_trust_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [local.operator_trust_statement]
  })
}

resource "aws_iam_role" "operator" {
  name                 = var.operator_role_name
  description          = "Short-lived human Terraform operator for VulnFlow infrastructure."
  assume_role_policy   = local.operator_trust_policy
  max_session_duration = 3600
  tags                 = local.common_tags
}

resource "aws_iam_policy" "operator" {
  for_each = local.policy_files

  name = {
    state = "VulnFlowTerraformStateOperator"
    app   = "VulnFlowApplicationOperator"
  }[each.key]
  description = {
    state = "Manage the dedicated VulnFlow Terraform state bucket and state objects."
    app   = "Manage only the reviewed VulnFlow demo application resources."
  }[each.key]
  policy = file("${path.module}/policies/${each.value}")
  tags   = local.common_tags
}

resource "aws_iam_role_policy_attachment" "operator" {
  for_each = aws_iam_policy.operator

  role       = aws_iam_role.operator.name
  policy_arn = each.value.arn
}
