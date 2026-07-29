# AWS roadmap

## Operating principle

VulnFlow uses:

```text
Permanent local development
+
Temporary and reproducible AWS deployment
+
terraform destroy after demonstrations
```

No AWS resources are part of phase one. The Terraform root is intentionally a
provider/variable skeleton.

## Phase 1: event contract

- Define and version the report-received event.
- Add Syft parsing and local SBOM persistence.
- Establish retry-safe idempotency and poison-message behavior in tests.
- Decide object naming, content-type, checksum, and maximum report size.

Exit criterion: a local adapter can redeliver events without duplicate findings.

## Phase 2: ingestion slice

- Create one encrypted private S3 bucket with short lifecycle retention.
- Create one encrypted SQS queue and DLQ with a reviewed redrive count.
- Package the Java ingestion Lambda with timeouts, memory, and reserved
  concurrency.
- Grant only object-read, queue-consume, and required data-write permissions.
- Add queue age/depth, DLQ, Lambda error/throttle, and cost alarms.

Exit criterion: a synthetic report reaches a temporary persistence target and
all resources are destroyed successfully.

## Phase 3: query storage and API

- Derive DynamoDB partition/sort keys and GSIs from endpoint access patterns.
- Use on-demand capacity initially and enable point-in-time recovery only after
  cost review.
- Add a separate least-privilege query Lambda and API Gateway.
- Add identity, authorization, throttling, and stable public error contracts.

Exit criterion: the local and cloud API contracts pass the same acceptance suite.

## Phase 4: enrichment and operations

- Correlate with a versioned CISA KEV data source.
- Add SNS alerts with controlled subscriptions and severity thresholds.
- Add EventBridge schedules only for concrete periodic work.
- Add CloudWatch dashboards, bounded log retention, tracing, and runbooks.
- Build a small read-only dashboard.

## Cost gates

Before each temporary deployment:

1. Review the Terraform plan and list every billable service.
2. Estimate request, storage, data transfer, log, and alarm volume.
3. Confirm no NAT Gateway, EC2, ECS, RDS, load balancer, or OpenSearch.
4. Configure AWS Budgets and operational alarms where appropriate.
5. Set an owner and fixed teardown time.

After the demonstration:

1. Run `terraform destroy`.
2. Verify S3 cleanup behavior and that no retained data blocks destruction.
3. Inspect the region for orphaned resources.
4. Review Cost Explorer after billing data becomes available.
5. Record actual cost and update estimates.

