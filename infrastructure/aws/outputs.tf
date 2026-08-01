output "report_bucket_name" {
  value = module.storage.bucket_name
}

output "ingestion_queue_url" {
  value = module.queue.queue_url
}

output "dead_letter_queue_url" {
  value = module.queue.dlq_url
}

output "lambda_function_name" {
  value = module.lambda.function_name
}
