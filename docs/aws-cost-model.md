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

## Risks

Unexpected retries, a poison message, large reports, retained DLQ messages, verbose logs, data transfer, failure to destroy, changing prices, and accidentally loosening caps can increase cost. S3 lifecycle is asynchronous and is not a substitute for destroy/residual-resource verification.

## Pre-apply checklist

- Accept the result-storage ADR and package exactly one reviewed provider.
- Confirm no VPC/NAT requirement is being introduced indirectly.
- Review current regional S3, SQS, Lambda, and CloudWatch pricing and account budgets/alerts.
- Inspect `terraform plan` manually in an authorized future change; this repository/phase never runs it.
- Set a unique bucket name, owner tags, short retention, caps, and a scheduled destroy time.
- Verify the operator can empty/delete the bucket and inspect residual resources.
- Confirm the test payload contains no secrets or personal data.
