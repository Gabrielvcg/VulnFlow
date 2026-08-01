output "table_name" {
  value = aws_dynamodb_table.results.name
}

output "table_arn" {
  value = aws_dynamodb_table.results.arn
}

output "gsi_arn" {
  value = "${aws_dynamodb_table.results.arn}/index/gsi1"
}
