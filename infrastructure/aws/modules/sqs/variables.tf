variable "name" { type = string }
variable "visibility_timeout_seconds" { type = number }
variable "message_retention_seconds" { type = number }
variable "dlq_retention_seconds" { type = number }
variable "max_receive_count" { type = number }
variable "additional_tags" { type = map(string) }
