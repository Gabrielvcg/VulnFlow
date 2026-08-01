# AWS temporary-slice cost model

This is a cost-control model, not a promise of zero cost. Prices, free tiers, taxes, data transfer, region, and account history vary and must be checked immediately before an apply.

## Assumed demonstration

One temporary environment in `eu-west-1`, at most 100 reports, each no larger than 10 MiB, one short processing invocation per delivery, batch size 5, reserved concurrency 2, three receives before DLQ, seven-day object/log retention, and destruction immediately after evidence collection.

| Resource | Charging behavior | Idle exposure/control |
|---|---|---|
| S3 | storage, requests, retrieval/transfer | Stored objects cost while retained; seven-day lifecycle and destroy cleanup. |
| SQS + DLQ | API requests and payload units | Empty queues generally have no request usage, but polling is controlled by Lambda event source; destroy both. |
| Lambda | requests and GB-seconds | No always-on compute; memory 512 MiB, timeout 30 s, concurrency 2. |
| CloudWatch Logs | ingestion, storage, queries | Logs persist until seven-day retention or destroy; avoid verbose payload logs. |
| DynamoDB | on-demand requests, storage, PITR | No fixed capacity; staged finding batches and retries consume writes. PITR adds storage cost when enabled. |
| CloudWatch alarm | metric alarm evaluation | The DLQ alarm is disabled by default and has no notification action. |
| IAM/event source | ordinarily no direct service charge | Still security-sensitive and must be destroyed. |

Avoided resources with meaningful idle or fixed cost include NAT Gateway, ALB, EC2, RDS, RDS Proxy, ECS, OpenSearch, Route 53, and a custom domain.

## Conservative variables

- `s3_lifecycle_days=7` (validated 1–30).
- `cloudwatch_log_retention_days=7`.
- `sqs_batch_size=5` (maximum 10).
- `lambda_reserved_concurrency=2` (validated maximum 5).
- `sqs_max_receive_count=3` (validated maximum 5).
- `max_payload_bytes=10485760` (hard maximum 10 MiB).
- `lambda_timeout_seconds=30`; visibility defaults to 180 seconds, six times the timeout.
- `dynamodb_max_findings=100000`; a report with `F` findings performs approximately `F` finding writes plus metadata transactions.
- `dynamodb_point_in_time_recovery_enabled=true`; review recovery storage cost before apply.
- `enable_dlq_alarm=false`; add a reviewed notification owner before operational use.

## Risks

Unexpected retries, duplicate outbox publication, staged DynamoDB batches, a poison message, large
reports, retained DLQ messages, PITR, verbose logs, data transfer, failure to destroy, changing prices,
and loosening caps can increase cost. Approximate work for `R` reports with `F` findings is proportional
to S3 bytes/requests + SQS request units + Lambda GB-seconds + `R * (F + metadata)` DynamoDB writes and
result reads. S3 lifecycle is asynchronous and is not a substitute for residual-resource verification.

## Pre-apply checklist

- Review ADR-019 through ADR-023 and set `result_store_provider="dynamodb"` explicitly.
- Confirm no VPC/NAT requirement is being introduced indirectly.
- Review current regional S3, SQS, Lambda, and CloudWatch pricing and account budgets/alerts.
- Inspect `terraform plan` manually in an authorized future change; this repository/phase never runs it.
- Set a unique bucket name, owner tags, short retention, caps, and a scheduled destroy time.
- Verify the operator can empty/delete the bucket and inspect residual resources.
- Confirm the test payload contains no secrets or personal data.
- Confirm DynamoDB PITR/deletion-protection choices and a DLQ alarm notification owner.
