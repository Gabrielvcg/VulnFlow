output "report_bucket_name" {
  value = module.storage.bucket_name
}

output "ingestion_queue_url" {
  value = module.queue.queue_url
}

output "dead_letter_queue_url" {
  value = module.queue.dlq_url
}

output "lambda_function_name" {
  value = module.lambda.function_name
}

output "result_table_name" {
  value = module.results.table_name
}

output "result_table_arn" {
  value = module.results.table_arn
}

output "vps_roles_anywhere_trust_anchor_arn" {
  description = "Trust anchor ARN when VPS Roles Anywhere is enabled."
  value       = try(module.vps_identity[0].trust_anchor_arn, null)
}

output "vps_roles_anywhere_profile_arn" {
  description = "Profile ARN when VPS Roles Anywhere is enabled."
  value       = try(module.vps_identity[0].profile_arn, null)
}

output "vps_roles_anywhere_role_arn" {
  description = "Least-privilege backend role ARN when VPS Roles Anywhere is enabled."
  value       = try(module.vps_identity[0].role_arn, null)
}
