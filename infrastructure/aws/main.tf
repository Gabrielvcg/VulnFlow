module "storage" {
  source = "./modules/s3"

  bucket_name     = var.report_bucket_name
  report_prefix   = var.report_prefix
  lifecycle_days  = var.s3_lifecycle_days
  force_destroy   = var.allow_destroy_non_empty_bucket
  additional_tags = local.common_tags
}

module "queue" {
  source = "./modules/sqs"

  name                       = local.name_prefix
  visibility_timeout_seconds = var.sqs_visibility_timeout_seconds
  message_retention_seconds  = var.sqs_message_retention_seconds
  dlq_retention_seconds      = var.sqs_dlq_retention_seconds
  max_receive_count          = var.sqs_max_receive_count
  additional_tags            = local.common_tags
}

module "results" {
  source = "./modules/dynamodb"

  table_name                     = "${local.name_prefix}-results"
  point_in_time_recovery_enabled = var.dynamodb_point_in_time_recovery_enabled
  deletion_protection_enabled    = var.dynamodb_deletion_protection_enabled
  additional_tags                = local.common_tags
}

module "lambda" {
  source = "./modules/lambda"

  function_name           = "${local.name_prefix}-processor"
  lambda_zip_path         = var.lambda_zip_path
  lambda_source_code_hash = var.lambda_source_code_hash
  handler                 = "com.vulnflow.aws.lambda.SqsVulnerabilityReportHandler::handleRequest"
  timeout_seconds         = var.lambda_timeout_seconds
  memory_size_mb          = var.lambda_memory_size_mb
  reserved_concurrency    = var.lambda_reserved_concurrency
  log_retention_days      = var.cloudwatch_log_retention_days
  queue_arn               = module.queue.queue_arn
  bucket_arn              = module.storage.bucket_arn
  bucket_name             = module.storage.bucket_name
  report_prefix           = var.report_prefix
  batch_size              = var.sqs_batch_size
  maximum_batching_window = var.sqs_maximum_batching_window_seconds
  maximum_concurrency     = var.sqs_maximum_concurrency
  max_payload_bytes       = var.max_payload_bytes
  result_store_provider   = var.result_store_provider
  result_table_name       = module.results.table_name
  result_table_arn        = module.results.table_arn
  result_table_gsi_arn    = module.results.gsi_arn
  dynamodb_max_findings   = var.dynamodb_max_findings
  additional_tags         = local.common_tags
}

module "vps_identity" {
  count  = var.enable_vps_roles_anywhere ? 1 : 0
  source = "./modules/rolesanywhere"

  name_prefix              = local.name_prefix
  aws_account_id           = var.aws_account_id
  aws_region               = var.aws_region
  ca_certificate_pem       = coalesce(var.roles_anywhere_ca_certificate_pem, "")
  certificate_subject_cn   = var.roles_anywhere_certificate_subject_cn
  session_duration_seconds = var.roles_anywhere_session_duration_seconds
  bucket_arn               = module.storage.bucket_arn
  report_prefix            = var.report_prefix
  queue_arn                = module.queue.queue_arn
  dlq_arn                  = module.queue.dlq_arn
  result_table_arn         = module.results.table_arn
  result_table_gsi_arn     = module.results.gsi_arn
  additional_tags          = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "dlq_visible_messages" {
  count = var.enable_dlq_alarm ? 1 : 0

  alarm_name          = "${local.name_prefix}-dlq-visible-messages"
  alarm_description   = "VulnFlow ingestion messages are visible in the dead-letter queue."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.dlq_alarm_threshold
  treat_missing_data  = "notBreaching"

  dimensions = {
    QueueName = module.queue.dlq_name
  }

  tags = local.common_tags
}
