variable "bucket_name" { type = string }
variable "report_prefix" { type = string }
variable "lifecycle_days" { type = number }
variable "force_destroy" { type = bool }
variable "additional_tags" { type = map(string) }
