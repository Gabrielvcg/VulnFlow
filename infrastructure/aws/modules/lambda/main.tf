resource "aws_cloudwatch_log_group" "processor" {
  name              = "/aws/lambda/${var.function_name}"
  retention_in_days = var.log_retention_days
  tags              = var.additional_tags
}

data "aws_iam_policy_document" "assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "processor" {
  name               = "${var.function_name}-role"
  assume_role_policy = data.aws_iam_policy_document.assume_role.json
  tags               = var.additional_tags
}

data "aws_iam_policy_document" "processor" {
  statement {
    sid       = "ReadReports"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${var.bucket_arn}/${var.report_prefix}*"]
  }
  statement {
    sid       = "ConsumeQueue"
    effect    = "Allow"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [var.queue_arn]
  }
  statement {
    sid    = "PersistAndReadResults"
    effect = "Allow"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:BatchWriteItem",
      "dynamodb:Query",
      "dynamodb:TransactWriteItems"
    ]
    resources = [var.result_table_arn, var.result_table_gsi_arn]
  }
  statement {
    sid       = "WriteFunctionLogs"
    effect    = "Allow"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.processor.arn}:*"]
  }
}

resource "aws_iam_role_policy" "processor" {
  name   = "${var.function_name}-least-privilege"
  role   = aws_iam_role.processor.id
  policy = data.aws_iam_policy_document.processor.json
}

resource "aws_lambda_function" "processor" {
  function_name                  = var.function_name
  role                           = aws_iam_role.processor.arn
  runtime                        = "java17"
  handler                        = var.handler
  filename                       = var.lambda_zip_path
  source_code_hash               = var.lambda_source_code_hash
  timeout                        = var.timeout_seconds
  memory_size                    = var.memory_size_mb
  reserved_concurrent_executions = var.reserved_concurrency
  tags                           = var.additional_tags

  environment {
    variables = {
      VULNFLOW_S3_BUCKET             = var.bucket_name
      VULNFLOW_S3_PREFIX             = trimsuffix(var.report_prefix, "/")
      VULNFLOW_MAX_PAYLOAD_BYTES     = tostring(var.max_payload_bytes)
      VULNFLOW_DYNAMODB_TABLE        = var.result_table_name
      VULNFLOW_DYNAMODB_MAX_FINDINGS = tostring(var.dynamodb_max_findings)
    }
  }

  lifecycle {
    precondition {
      condition     = var.result_store_provider == "dynamodb"
      error_message = "Set result_store_provider=dynamodb only after reviewing the packaged adapter and costs."
    }
  }

  depends_on = [aws_cloudwatch_log_group.processor, aws_iam_role_policy.processor]
}

resource "aws_lambda_event_source_mapping" "ingestion" {
  event_source_arn                   = var.queue_arn
  function_name                      = aws_lambda_function.processor.arn
  enabled                            = true
  batch_size                         = var.batch_size
  maximum_batching_window_in_seconds = var.maximum_batching_window
  function_response_types            = ["ReportBatchItemFailures"]
}
