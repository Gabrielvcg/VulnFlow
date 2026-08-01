variable "function_name" { type = string }
variable "lambda_zip_path" { type = string }
variable "lambda_source_code_hash" {
  type    = string
  default = null
}
variable "handler" { type = string }
variable "timeout_seconds" { type = number }
variable "memory_size_mb" { type = number }
variable "reserved_concurrency" { type = number }
variable "log_retention_days" { type = number }
variable "queue_arn" { type = string }
variable "bucket_arn" { type = string }
variable "bucket_name" { type = string }
variable "report_prefix" { type = string }
variable "batch_size" { type = number }
variable "maximum_batching_window" { type = number }
variable "maximum_concurrency" { type = number }
variable "max_payload_bytes" { type = number }
variable "result_store_provider" { type = string }
variable "result_table_name" { type = string }
variable "result_table_arn" { type = string }
variable "result_table_gsi_arn" { type = string }
variable "dynamodb_max_findings" { type = number }
variable "additional_tags" { type = map(string) }
