# AWS execution architecture

## Runtime separation

```text
LOCAL (default/prod)                       AWS (explicit profile)

LocalFileReportStorage                     S3ReportStorage
PostgreSQL ingestion_jobs                  PostgreSQL aws_publication_outbox -> SQS
LocalIngestionWorker                       Lambda SQS handler
PostgreSQL findings/results                DynamoDB results
        \                                  /
         +-> VulnerabilityReportProcessor <-+
             SHA-256 -> Trivy -> risk -> normalized findings
```

No AWS SDK client is constructed in local mode. The VPS deployment continues to activate only `prod`,
not `aws`. Lambda never connects to the VPS PostgreSQL database.

## Acceptance and publication

1. The API authenticates, validates multipart metadata and size, resolves the asset, reads bounded bytes,
   and calculates SHA-256.
2. `AwsScanSubmissionService` uploads to an internally generated S3 key with no PostgreSQL transaction.
3. `AwsScanRegistrationTransaction` resolves `(asset_id, content_hash)` under a row lock and atomically
   stores the scan plus immutable event in `aws_publication_outbox`.
4. A failed database transaction triggers best-effort S3 deletion. A duplicate candidate upload is also
   deleted after the existing event is selected.
5. The endpoint returns `202` with `eventId` and `publicationStatus=PUBLISH_PENDING` after the commit.
6. `AwsOutboxClaimService` uses `FOR UPDATE SKIP LOCKED`, assigns a UUID claim token, and commits.
7. `AwsOutboxPublisher` checks S3 and sends SQS after locks and transactions are released. Separate
   fenced transactions mark `PUBLISHED`, retry with bounded backoff, or terminal `FAILED`.

SQS success followed by a PostgreSQL acknowledgement failure is intentionally at-least-once: stale
recovery republishes and DynamoDB idempotency consumes the duplicate. A crash between S3 upload and the
outbox commit can leave an object that is removed by the configured S3 lifecycle. This is not a
distributed transaction.

## Lambda and DynamoDB

```text
SQSEvent record
  -> strict IngestionEventV1 decode
  -> strongly consistent event finalization check
  -> bounded S3 load + stored SHA-256 metadata check
  -> VulnerabilityReportProcessor (content SHA-256, JSON, normalization, risk)
  -> DynamoDbProcessingResultStore
       EVENT#eventId / META
       SCAN#scanId   / META (WRITING)
       SCAN#scanId   / FINDING#00000000 ... (batches of 25)
       transaction: EVENT + SCAN -> COMPLETED
  -> success or BatchItemFailure(messageId)
```

Readers hide findings unless scan metadata is `COMPLETED`. Permanent validation, missing payload, and
integrity failures atomically store `FAILED` event and scan markers and are then consumed. Transient S3
or DynamoDB failures are returned only for the affected SQS message. The same finalized `eventId` is a
successful duplicate; a reused identifier with different scan/asset/hash/scanner is a permanent conflict.

## Result queries and state ownership

Under `aws`, `GET /api/v1/scans/{id}` and `GET /api/v1/scans/{id}/findings?cursor=&size=` use
`ProcessingResultReader`. DynamoDB owns processing state, finding count, severity summary, and paginated
findings. Before a DynamoDB record exists, a short PostgreSQL projection reports `RECEIVED`,
`PUBLISH_PENDING`, `QUEUED`, or publication `FAILED`. PostgreSQL scan status is not treated as the final
AWS result state. Local query controllers continue to use PostgreSQL.

## Prepared infrastructure

Terraform declares private encrypted S3, encrypted SQS and DLQ, on-demand encrypted DynamoDB with a scan
partition key and asset GSI, Java 17 Lambda, a partial-batch event source, bounded logs, table/prefix/queue
scoped IAM, and an optional disabled-by-default DLQ depth alarm. Point-in-time recovery is configurable;
TTL is absent because results have no approved expiry policy.

It declares no VPC, NAT Gateway, ALB, EC2, RDS/Aurora, ECS/EKS, OpenSearch, Route 53, or API Gateway. An
apply remains blocked unless an operator explicitly sets `result_store_provider="dynamodb"` after review.
