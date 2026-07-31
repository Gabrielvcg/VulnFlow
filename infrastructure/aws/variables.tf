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
  default     = "../../aws/lambda-processor/target/vulnflow-lambda-processor-0.4.0.jar"
}

variable "lambda_source_code_hash" {
  description = "Optional base64 SHA-256 supplied by the packaging pipeline."
  type        = string
  default     = null
  nullable    = true
}

variable "result_store_provider_ready" {
  description = "Safety gate: true only after packaging exactly one reviewed Lambda result-store provider."
  type        = bool
  default     = false
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
  default = 2
  validation {
    condition     = var.lambda_reserved_concurrency >= 1 && var.lambda_reserved_concurrency <= 5
    error_message = "Temporary reserved concurrency must be between 1 and 5."
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
