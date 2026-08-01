# ADR-020: PostgreSQL outbox for AWS publication

Status: Accepted for 0.4.1.

## Decision

The backend uploads an internally named payload to S3 outside a database transaction. It then opens a
short PostgreSQL transaction that creates or resolves the deduplicated scan and inserts the immutable
`IngestionEventV1` into `aws_publication_outbox`. If that transaction fails, the backend attempts to
delete the candidate object. The HTTP response is returned after the PostgreSQL commit, not after SQS.

A scheduled publisher claims rows with `FOR UPDATE SKIP LOCKED`, assigns a random claim token, and
commits. It checks the S3 object and calls SQS with no PostgreSQL transaction or row lock. Separate short
transactions mark success or schedule bounded backoff. Claim tokens reject completion by stale
publishers, and stale `PUBLISHING` rows are recovered.

## Consequences

The design cannot provide a distributed S3/PostgreSQL/SQS transaction. SQS success followed by a failed
PostgreSQL acknowledgement produces a safe duplicate after recovery. A process death after S3 upload
but before PostgreSQL commit can leave an orphan until the S3 lifecycle removes it. An ambiguous database
commit can also make compensation unsafe; production operation must monitor both cases.
