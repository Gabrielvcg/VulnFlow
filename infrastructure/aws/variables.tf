variable "project_name" {
  description = "Short project name used in resource naming and tags."
  type        = string
  default     = "vulnflow"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,30}$", var.project_name))
    error_message = "project_name must use lowercase letters, numbers, and hyphens."
  }
}

variable "environment" {
  description = "Temporary deployment environment identifier."
  type        = string
  default     = "demo"

  validation {
    condition     = contains(["demo", "dev", "pre", "prod"], var.environment)
    error_message = "environment must be one of: demo, dev, pre, prod."
  }
}

variable "aws_region" {
  description = "AWS region for a future temporary deployment."
  type        = string
  default     = "eu-west-1"
}

variable "additional_tags" {
  description = "Extra tags merged into the required VulnFlow tags."
  type        = map(string)
  default     = {}
}

