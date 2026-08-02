# AWS roadmap

## 0.4.8 update

The executable path is active on the production VPS: the `prod,aws` profiles
select S3 storage, a PostgreSQL SQS publication outbox, Lambda, DynamoDB result
persistence, and DynamoDB result queries. The original local
filesystem/PostgreSQL worker path remains the repository default and is kept as
the tested rollback configuration.

Remote state has a separate bootstrap root and distinct bootstrap/application
keys. Human apply access uses an exact-user IAM trust and the
`VulnFlowTerraformOperator` AssumeRole session; AWS Organizations and IAM
Identity Center remain disabled. The optional VPS identity uses IAM Roles
Anywhere with a short-lived credential process. Neither identity is silently
enabled: the human role requires an explicitly authorized identity-bootstrap
apply, and the workload identity requires an explicit Terraform variable plus
a reviewed public CA certificate.

The controlled create-and-test exercise completed on 2026-08-02. Remote state,
the application slice, IAM Roles Anywhere, duplicate handling, retries, DLQ
redrive, and the public API path were verified. Destruction was not authorized;
the active resources and residual test records are documented in the runtime
activation record.

## Current position

VulnFlow 0.4.8 remains local by default in source configuration, while the
production VPS explicitly enables AWS mode. PostgreSQL supplies the publication
outbox, named volumes preserve local payloads and rollback data, and an independent Linux agent
uses a filesystem outbox for continuous scans. AWS SDK adapters and a Lambda
artifact are compiled but inactive by default; no credentials, AWS API calls,
or resources are used by default.

The local design now proves the behavior needed before cloud migration:

- durable job and payload registration;
- at-least-once execution with idempotent completion;
- bounded retries, dead letter, redrive, and stale lease recovery;
- concurrency safety against PostgreSQL;
- storage and queue adapter boundaries.
- offline agent scans, durable delivery retry, checksum verification, and
  idempotent external asset identity.

RabbitMQ was not added because it would create another runtime and operational
model while PostgreSQL already provides the required local persistence and
locking semantics.

## Proposed migration

Agent delivery is expected to evolve as follows:

```text
Agent -> presigned S3 upload -> SQS message -> Lambda processor
```

The agent must retain its local outbox until S3 confirms upload and the backend
accepts the submission envelope. Presigned URLs prevent long-lived AWS
credentials on VPS hosts. SQS receipt handles and visibility timeouts replace
database claim tokens only at the queue adapter boundary.

```text
0.2 local                         Future AWS adapter
-----------------------------    ------------------------------
ReportStorage payload key     -> encrypted private S3 object key
PostgreSQL IngestionJob       -> SQS message plus DLQ
@Scheduled local worker       -> Lambda event handler
availableAt/backoff           -> SQS visibility/delay behavior
DEAD_LETTER + redrive API     -> DLQ inspection/redrive workflow
Micrometer local metrics      -> CloudWatch metrics and alarms
```

Cloud migration must preserve scan/job state semantics and the same acceptance
suite. It must not introduce two simultaneous processing paths.

## Recommended phases

### Phase 1: contracts and processor boundaries (completed in 0.4.0)

- Define a versioned event envelope with scan ID, payload key, checksum, and
  scanner type. Completed as `IngestionEventV1`.
- Extract integrity, parsing, normalization, and risk into one shared processor.
- Add mock-tested S3/SQS adapters and a partial-batch Lambda handler.
- Define payload retention and deletion behavior locally.
- Add storage capacity metrics and an administrative cleanup policy.
- Decide how legacy completed and failed scans without 0.2 jobs are represented.

### Phase 2: authorized temporary ingestion slice

- Accept and implement the PostgreSQL result-store provider with event-id
  idempotency; keep the Terraform apply safety gate closed until then.
- Manually apply the prepared encrypted S3, SQS/DLQ, Lambda, IAM, logs, and
  event-source modules under the temporary deployment runbook.
- Apply least-privilege object read, queue consume, and database write access.
- Add queue age, DLQ depth, error, throttle, and cost alarms.

### Phase 3: query and identity

- Select a cloud query persistence model from demonstrated access patterns.
- Add API Gateway and a separate query boundary only if operationally justified.
- Replace the shared API key with an appropriate identity and authorization
  model before exposing a human-facing interface.

## Cost and safety gates

Before any future deployment:

1. Review `terraform plan` and list every billable service.
2. Confirm no NAT Gateway, EC2, ECS, RDS, load balancer, or OpenSearch unless a
   later ADR explicitly justifies it.
3. Define a fixed teardown time and owner.
4. Configure budgets, retention, and alarms.
5. Run `terraform destroy`, verify deletion, and inspect for orphaned resources.

Terraform in 0.4.8 manages the human identity bootstrap, S3/SQS/Lambda/DynamoDB
slice, its alarms, remote-state bootstrap, and Roles Anywhere identity. CI
formats, validates, tests, and resolves both local and AWS runtime
configurations; applying any plan remains a manual authorization gate.
