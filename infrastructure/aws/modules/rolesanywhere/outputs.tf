output "trust_anchor_arn" {
  value = aws_rolesanywhere_trust_anchor.vps.arn
}

output "profile_arn" {
  value = aws_rolesanywhere_profile.vps.arn
}

output "role_arn" {
  value = aws_iam_role.backend.arn
}
