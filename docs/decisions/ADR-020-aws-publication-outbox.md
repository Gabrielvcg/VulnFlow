# ADR-020: PostgreSQL outbox for AWS publication

Status: Accepted for 0.4.1.

## Decision

The backend uploads an internally named payload to S3 outside a database transaction. It then opens a
short PostgreSQL transaction that creates or resolves the deduplicated scan and inserts the immutable
`IngestionEventV1` into `aws_publication_outbox`. The backend attempts to delete the candidate object
only when registration returns successfully and confirms that it does not own that upload (a duplicate).
If registration throws, its commit outcome may be unknown: the backend retains the payload and
propagates the original exception without a cleanup attempt. The HTTP response is returned after the
PostgreSQL commit, not after SQS.

A scheduled publisher claims rows with `FOR UPDATE SKIP LOCKED`, assigns a random claim token, and
commits. It checks the S3 object and calls SQS with no PostgreSQL transaction or row lock. Separate short
transactions mark success or schedule bounded backoff. Claim tokens reject completion by stale
publishers, and stale `PUBLISHING` rows are recovered.

## Consequences

The design cannot provide a distributed S3/PostgreSQL/SQS transaction. SQS success followed by a failed
PostgreSQL acknowledgement produces a safe duplicate after recovery. A process death after S3 upload
but before PostgreSQL commit can leave an orphan. Retaining uploads on registration exceptions can
also leave orphan objects after definite rollbacks; this conservative cost avoids deleting a payload
that a committed outbox references after a lost commit acknowledgement. No orphan reconciler or
collector is implemented by this policy. Storage and retention costs remain until an applicable S3
lifecycle policy expires the object or a separately reviewed cleanup removes it. Lifecycle expiration
does not reconcile publication state and can also remove old referenced payloads.

The unit regression in
`backend/src/test/java/com/vulnflow/aws/ingestion/AwsScanSubmissionServiceTest.java` models a committed
reference followed by a thrown registration call. It is not a real PostgreSQL commit-loss experiment.

## Local validation on 23 October 2026

At baseline `29cf04f`, the modeled regression first failed because the candidate payload was deleted
despite the retained reference. After removing exception-driven compensation, all five submission
tests passed, covering the unknown outcome, an unreferenced failed registration, successful ownership,
known duplicate cleanup and cleanup failure without changing the duplicate result.

Reproduce the focused unit tests from the repository root:

```powershell
.\backend\mvnw.cmd --batch-mode --no-transfer-progress -f pom.xml -pl backend -am test '-Dtest=AwsScanSubmissionServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Full `.\backend\mvnw.cmd --batch-mode --no-transfer-progress -f pom.xml verify` was also attempted:
83 unit tests passed with no failures, errors or skips (7 core, 14 AWS adapters, 6 Lambda and 56 backend).
Backend integration setup failed in six classes because Testcontainers could not find a valid Docker
environment; the agent module was skipped after that failure. No database commit-loss injection,
AWS call or production test was performed. Integration behavior remains unverified by this run.
