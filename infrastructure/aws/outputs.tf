output "configuration_summary" {
  description = "Configuration-only output. No AWS resources are created in this phase."
  value = {
    name_prefix = local.name_prefix
    aws_region  = var.aws_region
    resources   = "none"
  }
}

