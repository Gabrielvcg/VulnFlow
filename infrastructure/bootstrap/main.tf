locals {
  common_tags = merge(
    {
      Project     = "vulnflow"
      Environment = "shared"
      Component   = "TerraformState"
      ManagedBy   = "Terraform"
    },
    var.additional_tags
  )

  state_bucket_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowExactTerraformOperatorBucketMetadata"
        Effect    = "Allow"
        Principal = { AWS = var.operator_role_arn }
        Action = [
          "s3:GetBucketOwnershipControls",
          "s3:GetBucketPolicyStatus",
          "s3:GetLifecycleConfiguration",
          "s3:GetReplicationConfiguration",
          "s3:ListBucket"
        ]
        Resource = "arn:aws:s3:::${var.state_bucket_name}"
      },
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          "arn:aws:s3:::${var.state_bucket_name}",
          "arn:aws:s3:::${var.state_bucket_name}/*"
        ]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      }
    ]
  })
}

resource "aws_s3_bucket" "terraform_state" {
  bucket        = var.state_bucket_name
  force_destroy = false

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_policy" "require_tls" {
  bucket = var.state_bucket_name
  policy = local.state_bucket_policy
}
