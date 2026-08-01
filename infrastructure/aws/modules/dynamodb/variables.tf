variable "table_name" { type = string }
variable "point_in_time_recovery_enabled" { type = bool }
variable "deletion_protection_enabled" { type = bool }
variable "additional_tags" { type = map(string) }
