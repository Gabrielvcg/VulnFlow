output "operator_role_arn" {
  description = "Role ARN used by the local vulnflow-admin AssumeRole profile."
  value       = aws_iam_role.operator.arn
}

output "operator_policy_arns" {
  description = "Scoped customer-managed policies attached to the operator role."
  value       = { for key, policy in aws_iam_policy.operator : key => policy.arn }
}

output "trusted_user_arn" {
  description = "Exact IAM user accepted by the operator trust policy."
  value       = var.trusted_user_arn
}

output "mfa_required" {
  description = "Whether this reviewed bootstrap plan enforces MFA on AssumeRole."
  value       = var.mfa_serial != null
}
