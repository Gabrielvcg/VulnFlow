resource "aws_rolesanywhere_trust_anchor" "vps" {
  name    = "${var.name_prefix}-vps"
  enabled = true
  tags    = var.additional_tags

  source {
    source_type = "CERTIFICATE_BUNDLE"
    source_data {
      x509_certificate_data = var.ca_certificate_pem
    }
  }

  lifecycle {
    precondition {
      condition     = length(trimspace(var.ca_certificate_pem)) > 0
      error_message = "A reviewed public CA certificate is required when VPS Roles Anywhere is enabled."
    }
  }
}

data "aws_iam_policy_document" "backend_assume_role" {
  statement {
    sid    = "AllowRolesAnywhereSession"
    effect = "Allow"
    actions = [
      "sts:AssumeRole",
      "sts:SetSourceIdentity",
      "sts:TagSession"
    ]

    principals {
      type        = "Service"
      identifiers = ["rolesanywhere.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.aws_account_id]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_rolesanywhere_trust_anchor.vps.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:PrincipalTag/x509Subject/CN"
      values   = [var.certificate_subject_cn]
    }
  }
}

resource "aws_iam_role" "backend" {
  name                 = "${var.name_prefix}-backend-role"
  assume_role_policy   = data.aws_iam_policy_document.backend_assume_role.json
  max_session_duration = 3600
  tags                 = var.additional_tags
}

data "aws_iam_policy_document" "backend_access" {
  statement {
    sid    = "ManageReportPayloads"
    effect = "Allow"
    actions = [
      "s3:DeleteObject",
      "s3:GetObject",
      "s3:PutObject"
    ]
    resources = ["${var.bucket_arn}/${var.report_prefix}*"]
  }

  statement {
    sid       = "PublishIngestionEvents"
    effect    = "Allow"
    actions   = ["sqs:SendMessage"]
    resources = [var.queue_arn]
  }

  statement {
    sid       = "ReadQueueTelemetry"
    effect    = "Allow"
    actions   = ["sqs:GetQueueAttributes"]
    resources = [var.queue_arn, var.dlq_arn]
  }

  statement {
    sid    = "ReadProcessingResults"
    effect = "Allow"
    actions = [
      "dynamodb:BatchGetItem",
      "dynamodb:GetItem",
      "dynamodb:Query"
    ]
    resources = [var.result_table_arn, var.result_table_gsi_arn]
  }
}

resource "aws_iam_policy" "backend" {
  name   = "${var.name_prefix}-backend-least-privilege"
  policy = data.aws_iam_policy_document.backend_access.json
  tags   = var.additional_tags
}

resource "aws_iam_role_policy_attachment" "backend" {
  role       = aws_iam_role.backend.name
  policy_arn = aws_iam_policy.backend.arn
}

resource "aws_rolesanywhere_profile" "vps" {
  name                        = "${var.name_prefix}-vps"
  enabled                     = true
  accept_role_session_name    = false
  require_instance_properties = false
  duration_seconds            = var.session_duration_seconds
  role_arns                   = [aws_iam_role.backend.arn]
  managed_policy_arns         = [aws_iam_policy.backend.arn]
  tags                        = var.additional_tags
}
