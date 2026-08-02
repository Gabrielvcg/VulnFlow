variable "project_name" {
  description = "Short project name used in resource naming and tags."
  type        = string
  default     = "vulnflow"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,30}$", var.project_name))
    error_message = "project_name must use lowercase letters, numbers, and hyphens."
  }
}

variable "environment" {
  description = "Temporary deployment environment identifier."
  type        = string
  default     = "demo"

  validation {
    condition     = contains(["demo", "dev", "pre", "prod"], var.environment)
    error_message = "environment must be one of: demo, dev, pre, prod."
  }
}

variable "aws_region" {
  description = "AWS region for a future temporary deployment."
  type        = string
  default     = "eu-west-1"
}

variable "aws_account_id" {
  description = "AWS account that owns the temporary VulnFlow resources."
  type        = string
  default     = "160172542031"

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id must contain exactly 12 digits."
  }
}

variable "additional_tags" {
  description = "Extra tags merged into the required VulnFlow tags."
  type        = map(string)
  default     = {}
}

variable "report_bucket_name" {
  description = "Globally unique private bucket name selected by the operator."
  type        = string
}

variable "report_prefix" {
  type    = string
  default = "reports/"
  validation {
    condition = (
      can(regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,254}/$", var.report_prefix)) &&
      !strcontains(var.report_prefix, "..") &&
      !strcontains(var.report_prefix, "//")
    )
    error_message = "report_prefix must be a safe logical prefix ending in one slash."
  }
}

variable "lambda_zip_path" {
  description = "Path to the shaded Lambda JAR produced by Maven."
  type        = string
  default     = "../../aws/lambda-processor/target/vulnflow-lambda-processor-0.4.8.jar"
}

variable "lambda_source_code_hash" {
  description = "Optional base64 SHA-256 supplied by the packaging pipeline."
  type        = string
  default     = null
  nullable    = true
}

variable "result_store_provider" {
  description = "Explicit deployment gate. Set to dynamodb only after reviewing the packaged adapter and costs."
  type        = string
  default     = "none"

  validation {
    condition     = contains(["none", "dynamodb"], var.result_store_provider)
    error_message = "result_store_provider must be none or dynamodb."
  }
}

variable "dynamodb_max_findings" {
  type    = number
  default = 100000
  validation {
    condition     = var.dynamodb_max_findings >= 1 && var.dynamodb_max_findings <= 100000
    error_message = "dynamodb_max_findings must be between 1 and 100000."
  }
}

variable "dynamodb_point_in_time_recovery_enabled" {
  type    = bool
  default = true
}

variable "dynamodb_deletion_protection_enabled" {
  type    = bool
  default = false
}

variable "enable_dlq_alarm" {
  description = "Creates a metric alarm without notification actions when enabled."
  type        = bool
  default     = false
}

variable "dlq_alarm_threshold" {
  type    = number
  default = 1
  validation {
    condition     = var.dlq_alarm_threshold >= 1
    error_message = "dlq_alarm_threshold must be positive."
  }
}

variable "s3_lifecycle_days" {
  type    = number
  default = 7
  validation {
    condition     = var.s3_lifecycle_days >= 1 && var.s3_lifecycle_days <= 30
    error_message = "Temporary report retention must be between 1 and 30 days."
  }
}

variable "cloudwatch_log_retention_days" {
  type    = number
  default = 7
  validation {
    condition     = contains([1, 3, 5, 7, 14, 30], var.cloudwatch_log_retention_days)
    error_message = "Temporary log retention must be one of: 1, 3, 5, 7, 14, or 30 days."
  }
}

variable "lambda_timeout_seconds" {
  type    = number
  default = 30
  validation {
    condition     = var.lambda_timeout_seconds >= 1 && var.lambda_timeout_seconds <= 60
    error_message = "The demo Lambda timeout must be between 1 and 60 seconds."
  }
}

variable "sqs_visibility_timeout_seconds" {
  type    = number
  default = 180
  validation {
    condition     = var.sqs_visibility_timeout_seconds >= var.lambda_timeout_seconds * 6
    error_message = "SQS visibility timeout must be at least six times the Lambda timeout."
  }
}

variable "lambda_memory_size_mb" {
  type    = number
  default = 512
  validation {
    condition     = var.lambda_memory_size_mb >= 256 && var.lambda_memory_size_mb <= 1024
    error_message = "The demo memory cap must be between 256 and 1024 MB."
  }
}

variable "lambda_reserved_concurrency" {
  type    = number
  default = -1
  validation {
    condition = (
      var.lambda_reserved_concurrency == -1 ||
      (var.lambda_reserved_concurrency >= 1 && var.lambda_reserved_concurrency <= 5)
    )
    error_message = "Reserved concurrency must be -1 (unreserved) or between 1 and 5."
  }
}

variable "sqs_maximum_concurrency" {
  description = "Maximum concurrent Lambda invocations from the SQS event source."
  type        = number
  default     = 2
  validation {
    condition     = var.sqs_maximum_concurrency >= 2 && var.sqs_maximum_concurrency <= 5
    error_message = "SQS maximum concurrency must be between 2 and 5."
  }
}

variable "sqs_batch_size" {
  type    = number
  default = 5
  validation {
    condition     = var.sqs_batch_size >= 1 && var.sqs_batch_size <= 10
    error_message = "Batch size must be between 1 and 10."
  }
}

variable "sqs_maximum_batching_window_seconds" {
  type    = number
  default = 1
  validation {
    condition     = var.sqs_maximum_batching_window_seconds >= 0 && var.sqs_maximum_batching_window_seconds <= 5
    error_message = "The demo batching window must be between 0 and 5 seconds."
  }
}

variable "sqs_max_receive_count" {
  type    = number
  default = 3
  validation {
    condition     = var.sqs_max_receive_count >= 1 && var.sqs_max_receive_count <= 5
    error_message = "Maximum receives must be between 1 and 5."
  }
}

variable "sqs_message_retention_seconds" {
  type    = number
  default = 86400
  validation {
    condition     = var.sqs_message_retention_seconds >= 60 && var.sqs_message_retention_seconds <= 1209600
    error_message = "SQS retention must be between 60 seconds and 14 days."
  }
}

variable "sqs_dlq_retention_seconds" {
  type    = number
  default = 345600
  validation {
    condition = (
      var.sqs_dlq_retention_seconds >= var.sqs_message_retention_seconds &&
      var.sqs_dlq_retention_seconds <= 1209600
    )
    error_message = "DLQ retention must be at least the source retention and no more than 14 days."
  }
}

variable "max_payload_bytes" {
  type    = number
  default = 10485760
  validation {
    condition     = var.max_payload_bytes >= 1024 && var.max_payload_bytes <= 10485760
    error_message = "Payload limit must be between 1 KiB and 10 MiB."
  }
}

variable "allow_destroy_non_empty_bucket" {
  description = "Temporary-only switch that permits destroy to remove retained reports."
  type        = bool
  default     = true
}

variable "enable_vps_roles_anywhere" {
  description = "Creates the optional VPS Roles Anywhere trust anchor, profile, and least-privilege role."
  type        = bool
  default     = false
}

variable "roles_anywhere_ca_certificate_pem" {
  description = "Public PEM certificate for the external CA that issues the VPS workload certificate."
  type        = string
  default     = null
  nullable    = true
  sensitive   = true

  validation {
    condition = (
      var.roles_anywhere_ca_certificate_pem == null ||
      can(regex("^-----BEGIN CERTIFICATE-----[\\s\\S]+-----END CERTIFICATE-----\\s*$", var.roles_anywhere_ca_certificate_pem))
    )
    error_message = "roles_anywhere_ca_certificate_pem must be null or a PEM-encoded certificate."
  }
}

variable "roles_anywhere_certificate_subject_cn" {
  description = "Exact X.509 subject CN accepted by the VPS role trust policy."
  type        = string
  default     = "vulnflow-backend.vacaro.es"

  validation {
    condition     = can(regex("^[A-Za-z0-9][A-Za-z0-9.-]{2,252}[A-Za-z0-9]$", var.roles_anywhere_certificate_subject_cn))
    error_message = "roles_anywhere_certificate_subject_cn must be a safe DNS-style common name."
  }
}

variable "roles_anywhere_session_duration_seconds" {
  description = "Maximum lifetime of VPS temporary AWS sessions."
  type        = number
  default     = 900

  validation {
    condition     = var.roles_anywhere_session_duration_seconds >= 900 && var.roles_anywhere_session_duration_seconds <= 3600
    error_message = "Roles Anywhere sessions must last between 15 and 60 minutes."
  }
}
