output "state_bucket_name" {
  description = "Dedicated S3 bucket for VulnFlow Terraform state."
  value       = aws_s3_bucket.terraform_state.id
}

output "state_bucket_arn" {
  description = "ARN of the dedicated Terraform state bucket."
  value       = aws_s3_bucket.terraform_state.arn
}

output "state_region" {
  description = "Region used by the S3 backend."
  value       = var.aws_region
}

output "application_state_key" {
  description = "Object key reserved for the VulnFlow demo application state."
  value       = var.application_state_key
}
