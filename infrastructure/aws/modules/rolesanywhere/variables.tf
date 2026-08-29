variable "name_prefix" {
  type = string
}

variable "aws_account_id" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "ca_certificate_pem" {
  type      = string
  sensitive = true
}

variable "certificate_subject_cn" {
  type = string
}

variable "session_duration_seconds" {
  type = number
}

variable "bucket_arn" {
  type = string
}

variable "report_prefix" {
  type = string
}

variable "queue_arn" {
  type = string
}

variable "dlq_arn" {
  type = string
}

variable "result_table_arn" {
  type = string
}

variable "result_table_gsi_arn" {
  type = string
}

variable "additional_tags" {
  type    = map(string)
  default = {}
}
