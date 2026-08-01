variable "aws_region" {
  description = "AWS region for the Terraform state bucket."
  type        = string
  default     = "eu-west-1"

  validation {
    condition     = var.aws_region == "eu-west-1"
    error_message = "The VulnFlow Terraform state bucket must remain in eu-west-1."
  }
}

variable "state_bucket_name" {
  description = "Globally unique S3 bucket name dedicated to VulnFlow Terraform state."
  type        = string

  validation {
    condition = (
      length(var.state_bucket_name) >= 3 &&
      length(var.state_bucket_name) <= 63 &&
      can(regex("^[a-z0-9][a-z0-9.-]*[a-z0-9]$", var.state_bucket_name)) &&
      strcontains(var.state_bucket_name, "vulnflow") &&
      strcontains(var.state_bucket_name, "terraform-state")
    )
    error_message = "The bucket name must be a valid S3 name containing vulnflow and terraform-state."
  }
}

variable "application_state_key" {
  description = "Object key reserved for the VulnFlow demo application state."
  type        = string
  default     = "vulnflow/demo/terraform.tfstate"

  validation {
    condition     = startswith(var.application_state_key, "vulnflow/") && endswith(var.application_state_key, "/terraform.tfstate")
    error_message = "The application state key must stay under vulnflow/ and end in /terraform.tfstate."
  }
}

variable "additional_tags" {
  description = "Additional non-sensitive tags for the state bucket."
  type        = map(string)
  default     = {}
}
