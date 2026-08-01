variable "aws_account_id" {
  description = "AWS account that owns the VulnFlow Terraform operator role."
  type        = string
  default     = "160172542031"

  validation {
    condition     = var.aws_account_id == "160172542031"
    error_message = "The operator role must remain in the reviewed VulnFlow AWS account."
  }
}

variable "aws_region" {
  description = "AWS region used by the VulnFlow Terraform workloads."
  type        = string
  default     = "eu-west-1"

  validation {
    condition     = var.aws_region == "eu-west-1"
    error_message = "The VulnFlow Terraform operator must remain in eu-west-1."
  }
}

variable "trusted_user_arn" {
  description = "Exact bootstrap IAM user allowed to assume the operator role."
  type        = string
  default     = "arn:aws:iam::160172542031:user/vacaro"

  validation {
    condition     = var.trusted_user_arn == "arn:aws:iam::160172542031:user/vacaro"
    error_message = "Only the reviewed vacaro IAM user may bootstrap this operator role."
  }
}

variable "mfa_serial" {
  description = "Optional real MFA device ARN. Keep null until the trusted IAM user has an assigned MFA device."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition = (
      var.mfa_serial == null ||
      can(regex("^arn:aws:iam::160172542031:mfa/[A-Za-z0-9+=,.@_-]+$", var.mfa_serial))
    )
    error_message = "mfa_serial must be null or an MFA device ARN in the reviewed account."
  }
}

variable "operator_role_name" {
  description = "Fixed name of the human Terraform operator role."
  type        = string
  default     = "VulnFlowTerraformOperator"

  validation {
    condition     = var.operator_role_name == "VulnFlowTerraformOperator"
    error_message = "The operator role name is part of the local preflight contract and cannot change here."
  }
}

variable "additional_tags" {
  description = "Additional non-sensitive tags for the identity bootstrap resources."
  type        = map(string)
  default     = {}
}
