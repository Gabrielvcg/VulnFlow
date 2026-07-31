resource "aws_s3_bucket" "reports" {
  bucket        = var.bucket_name
  force_destroy = var.force_destroy
  tags          = var.additional_tags
}

resource "aws_s3_bucket_public_access_block" "reports" {
  bucket = aws_s3_bucket.reports.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "reports" {
  bucket = aws_s3_bucket.reports.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "reports" {
  bucket = aws_s3_bucket.reports.id
  versioning_configuration {
    status = "Suspended"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "reports" {
  bucket = aws_s3_bucket.reports.id
  rule {
    id     = "expire-temporary-reports"
    status = "Enabled"
    filter {
      prefix = var.report_prefix
    }
    expiration {
      days = var.lifecycle_days
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}
