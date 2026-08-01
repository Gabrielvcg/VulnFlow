# Prepared AWS architecture

## Runtime comparison

```text
LOCAL                                      AWS FUTURE SLICE

LocalFileReportStorage                     S3ReportStorage
PostgreSQL ingestion_jobs                  SQS
LocalIngestionWorker                       Lambda SQS handler
DEAD_LETTER in PostgreSQL                  SQS DLQ
claimToken + row lock                      receipt handle + visibility timeout
scheduled stale recovery                   SQS redelivery after visibility expiry
        \                                  /
         +-> VulnerabilityReportProcessor <-+
             integrity -> parse -> risk -> normalized findings
```

`ReportStorage`, `ProcessingResultStore`, and `IngestionMessagePublisher` are substitution ports. Local completion uses JPA and a claim context. Lambda passes the immutable event to a future idempotent result-store provider.

The local and AWS concepts are not identical. SQS does not expose a row state machine, a receipt handle is not a claim generation, DLQ routing is not an application transaction, and visibility timeout does not atomically commit results. `eventId` uniqueness at the result-store boundary closes the duplicate-delivery gap.

## Lambda batch flow

```text
SQSEvent
  -> validate each IngestionEventV1
  -> ReportStorage.load(payloadKey)
  -> VulnerabilityReportProcessor
       -> SHA-256 verification
       -> Trivy parsing and bounds
       -> risk calculation
       -> normalized findings
  -> ProcessingResultStore.store(event, result)
  -> success or BatchItemFailure(messageId)
```

There is no polling, sleep, application retry, PostgreSQL claim, receipt-handle access, or manual DLQ publisher in the handler.

## Prepared Terraform resources

- One private, AES-256 encrypted S3 bucket with public access blocked and configurable expiry.
- One SQS ingestion queue encrypted with SQS-managed encryption.
- One encrypted SQS DLQ, redrive policy, and redrive allow policy.
- One Java 17 Lambda function with bounded memory, timeout, and reserved concurrency.
- One least-privilege IAM execution role/policy scoped to the report prefix, ingestion queue, and function log group.
- One CloudWatch log group with limited retention.
- One SQS event source mapping with partial batch responses.

No VPC, NAT, ALB, EC2, RDS, ECS, OpenSearch, Route 53, domain, or API Gateway is declared.
