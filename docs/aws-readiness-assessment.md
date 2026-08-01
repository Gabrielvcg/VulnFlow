# AWS readiness assessment

## Scope and evidence

This assessment describes the code on `main` at commit `beca0ca` before the 0.4.0 refactor. It is based on direct inspection of the backend source, migrations, configuration, and tests performed before changing the architecture. The generated service graph in `.codex/api-service-graph/` captures the resulting 0.4.0 topology and is complementary evidence; the baseline conclusions below do not depend on that post-refactor graph.

## Current ingestion path

```text
HTTP multipart request
  -> ScanIngestionController
  -> DefaultScanIngestionService
  -> ScanRegistrationService
       -> PostgreSQL Scan + IngestionJob
       -> LocalFileReportStorage

Spring scheduler
  -> LocalIngestionWorker
  -> JobClaimService
       -> PostgreSQL FOR UPDATE SKIP LOCKED
  -> IngestionJobProcessor
       -> LocalFileReportStorage
       -> PayloadIntegrityVerifier
       -> TrivyVulnerabilityReportParser
       -> IngestionPersistenceService
            -> PostgreSQL Finding + Scan + IngestionJob
```

The filesystem write and PostgreSQL registration are not a distributed transaction. `ScanRegistrationService` compensates a database rollback by registering a transaction synchronization that deletes the stored payload, but a process crash between the file write and transaction completion can still leave an orphan.

## Coupling inventory

| Concern | Directly coupled code | Evidence and consequence |
|---|---|---|
| PostgreSQL as a queue | `IngestionJob`, `IngestionJobRepository`, `JobClaimService`, `JobFailureService`, `IngestionJobRecoveryService`, `IngestionJobRedriveService`, `LocalIngestionWorker` | Queue states, attempt accounting, claim fencing, recovery, and redrive are represented as JPA rows and PostgreSQL queries. These are local-adapter semantics, not reusable processing rules. |
| JPA | Domain entities under `backend/.../domain`, repositories under `backend/.../repository`, `IngestionPersistenceService` | The completion path constructs and saves JPA `Finding` entities directly. This currently prevents the processing result from being persisted by a non-JPA adapter. |
| Local filesystem | `LocalFileReportStorage`, `ReportStorageProperties`, `ScanRegistrationService` | Payload keys are safe internal relative keys, but creation, atomic move fallback, and deletion compensation are filesystem-specific. |
| Spring scheduling | `LocalIngestionWorker`, scheduling enabled by `VulnFlowApplication`, `WorkerProperties` | Polling cadence and recovery are local worker orchestration. Lambda must be invoked by an SQS event source rather than copy this loop. |
| HTTP | `ScanIngestionController`, `DefaultScanIngestionService`, multipart DTO/controller validation | Multipart buffering, content type, filename normalization, and HTTP responses belong to the inbound API adapter. They must not enter the shared processor or Lambda handler. |
| `IngestionJob` states | Claim, failure, recovery, redrive, query DTOs, metrics, and completion services | `PENDING`, `PROCESSING`, `RETRY_WAIT`, `COMPLETED`, and `DEAD_LETTER` describe the local PostgreSQL queue. SQS has messages, visibility, receive count, retention, and a DLQ; the state models are not interchangeable. |
| SQL locks | `IngestionJobRepository`, `ScanRepository`, claim/failure/recovery/redrive/completion services | Pessimistic row locks fence local state transitions. An SQS handler receives a receipt handle from the runtime and must not reproduce these locks merely to claim work. |
| `FOR UPDATE SKIP LOCKED` | Native claim and stale-recovery queries in `IngestionJobRepository` | This is the concurrency primitive for multiple local workers/backend instances. It has no SQS equivalent to port. |
| Local paths | `LocalFileReportStorage`, `ReportStorageProperties`, local YAML settings | Physical root paths are configuration of the local adapter. They must never be placed in the ingestion event. |
| Spring transactions | `ScanRegistrationService`, `JobClaimService`, `IngestionPersistenceService`, `JobFailureService`, `IngestionJobRecoveryService`, `IngestionJobRedriveService` | Transaction boundaries protect local database transitions. A Lambda/SQS delivery boundary is not a Spring transaction and needs an idempotent result-store operation instead. |

## Reusable domain and processing logic

The following behavior is infrastructure-neutral and should form the shared processing core:

- SHA-256 integrity verification from `PayloadIntegrityVerifier`.
- Trivy JSON validation, bounds, and normalization from `TrivyVulnerabilityReportParser`.
- Parsed report/value records.
- Severity representation and the risk calculation in `DefaultFindingRiskCalculator`.
- A new orchestration operation that accepts payload bytes plus scan identity and metadata, then returns normalized findings.

Before 0.4.0 these pieces are individually close to pure code, but their orchestration is split: `IngestionJobProcessor` loads from storage and `IngestionPersistenceService` performs risk calculation while constructing JPA entities. The split is the primary blocker to exact reuse by Lambda.

## Local adapters that remain supported

- `ScanIngestionController` and `DefaultScanIngestionService`: HTTP/multipart input.
- `LocalFileReportStorage`: default payload storage.
- `IngestionJob` and its repositories/services: PostgreSQL-backed local queue.
- `LocalIngestionWorker`: scheduled local consumer.
- JPA result persistence: atomic local completion of findings, scan, and job.
- Local retry, stale recovery, manual redrive, and PostgreSQL dead-letter state.

These components are valid for the local and VPS modes. Their infrastructure semantics are intentionally not promoted into the shared core.

## AWS substitutions

| Port or responsibility | Local implementation | AWS implementation or service |
|---|---|---|
| `ReportStorage` | `LocalFileReportStorage` | `S3ReportStorage` |
| `ProcessingResultStore` | PostgreSQL/JPA completion adapter | First-slice PostgreSQL adapter; possible later DynamoDB adapter |
| `IngestionMessagePublisher` | Not required by the current database registration path | `SqsIngestionMessagePublisher` |
| Queue | `ingestion_jobs` table | SQS queue |
| Consumer runtime | `LocalIngestionWorker` | SQS event source plus Lambda handler |
| Dead letter | `DEAD_LETTER` job state | SQS DLQ and redrive policy |
| Claim fencing | SQL lock plus `claimToken` | Receipt handle plus visibility timeout, with result idempotency |
| Abandoned work | Scheduled stale recovery | Visibility expiration and SQS redelivery |

The mappings are conceptual, not one-to-one. In particular, a receipt handle is delivery-scoped and is not a persisted generation token; visibility timeout does not provide an atomic transaction with result storage; SQS DLQ movement is managed by SQS rather than an application state transition.

## Interfaces deliberately not introduced

- `IngestionMessageConsumer`: the Lambda runtime and the local scheduled worker already provide materially different inbound adapters. A shared consumer interface would hide useful delivery semantics without replacing either runtime.
- `DeadLetterPublisher`: local dead-lettering is a database transition, while AWS redrive is an SQS policy. A common publisher would duplicate or compete with SQS.
- `ScanStateStore`: result and scan completion must be one atomic persistence capability. Splitting scan state from `ProcessingResultStore` would make partial completion easier, not safer.

## Code that must not be copied to Lambda

- `LocalIngestionWorker` polling and `@Scheduled` configuration.
- Claim queries, `JobClaimService`, `claimToken`, and `FOR UPDATE SKIP LOCKED`.
- Local retry/backoff calculation, stale-job recovery, and manual dead-letter transitions.
- Multipart/HTTP validation and controller DTOs.
- Filesystem paths and filesystem move/delete compensation.
- JPA entities or a long-lived database connection strategy embedded in the handler.

Lambda should contain only the SQS batch adapter, event validation, `ReportStorage` invocation, the common processor call, and a `ProcessingResultStore` invocation. Delivery failure is signaled per SQS record so SQS owns retry and DLQ routing.

## Technical blockers before the refactor

1. **Processing is not one reusable capability.** Risk calculation and normalized-result creation occur in `IngestionPersistenceService`, which is JPA- and claim-token-aware.
2. **There is no infrastructure-neutral result contract.** The processor hands a parsed report directly to JPA completion code.
3. **There is no stable, versioned message contract.** Local jobs pass an internal `JobClaim` record containing database-specific fields.
4. **There is no S3 or SQS adapter boundary/configuration.** Local storage is the only `ReportStorage` implementation and is created unconditionally.
5. **The build has no independently packageable Lambda artifact.** Backend and agent are separate Maven projects, but there is no shared-core or Lambda module.
6. **Registration spans filesystem and PostgreSQL.** Compensation reduces normal rollback leakage but cannot make the two resources atomic.
7. **Idempotency is currently local-job-specific.** A Lambda result store must reject duplicate `eventId` processing independently of SQS delivery count.
8. **AWS result persistence is undecided.** PostgreSQL preserves the existing query model but needs connection controls; DynamoDB changes the API read model and is intentionally outside this phase.

## Refactor boundary

The 0.4.0 change should extract the pure processor and contracts, make local completion implement the result-store port, and add disabled-by-default AWS adapters plus a Lambda batch adapter. It should not change the local queue state machine, introduce an AWS deployment, or pretend that a production Lambda-to-result-store implementation has been selected before the result-storage ADR is accepted.
