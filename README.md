# VulnFlow

VulnFlow is a local-first, event-ready platform for receiving, normalizing,
storing, and querying vulnerability scan results. This portfolio project starts
with synchronous Trivy JSON ingestion in a modular Spring Boot backend and is
designed to evolve toward a temporary, reproducible serverless AWS deployment.

The project demonstrates Java and Spring Boot, PostgreSQL persistence,
asynchronous architecture boundaries, Docker/Linux workflows, infrastructure as
code, DevSecOps controls, observability, and cost-aware cloud design.

## Current local architecture

```text
Trivy JSON
    |
    | multipart/form-data
    v
Spring Boot modular monolith
    |-- validate, hash, deduplicate
    |-- parse and calculate risk
    |-- persist scan and findings
    v
PostgreSQL
    ^
    |
REST query API + Swagger + Actuator
```

The ingestion controller delegates to `ScanIngestionService`. Parsing is behind
`VulnerabilityReportParser`, and scoring is behind `FindingRiskCalculator`.
These boundaries allow a later SQS/Lambda consumer to reuse the domain behavior
without placing queue concerns in the HTTP layer.

## Target AWS architecture

```text
Agent on VPS or workstation
    |
    | Trivy JSON + Syft SBOM
    v
Amazon S3
    |
    v
Amazon SQS + DLQ
    |
    v
AWS Lambda (Java)
    |
    | normalize, deduplicate, prioritize
    v
Amazon DynamoDB
    |
    v
API Gateway + Lambda
    |
    v
Simple dashboard
```

EventBridge Scheduler, SNS, and CloudWatch are planned. They are not simulated
locally and no AWS resources are defined in this phase.

## Technology

- Java 17 and Spring Boot 3.5
- Spring MVC, Validation, Data JPA, and Actuator
- PostgreSQL 16 and Flyway
- Jackson and springdoc OpenAPI/Swagger UI
- Maven Wrapper, JUnit 5, Mockito, and Testcontainers
- Docker Compose and multi-stage container builds
- Terraform configuration skeleton
- GitHub Actions verification and container build

## Start from scratch

Requirements: Docker Desktop or Docker Engine with Compose v2. No local Java,
Maven, PostgreSQL, Terraform, or AWS credentials are required for the Compose
workflow.

```bash
cp .env.example .env
docker compose up --build
```

PowerShell equivalent:

```powershell
Copy-Item .env.example .env
docker compose up --build
```

Wait until both services are healthy:

```bash
docker compose ps
curl http://localhost:8080/actuator/health
```

Useful URLs:

- API: `http://localhost:8080/api/v1`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Health: `http://localhost:8080/actuator/health`

Stop the services with `docker compose down`. Add `--volumes` only when you
intentionally want to delete local PostgreSQL data.

## Run the demo

After `docker compose up --build` reports a healthy backend:

```bash
sh scripts/demo.sh
```

or:

```bash
make demo
```

The script creates a container-image asset, uploads
`sample-data/trivy-multiple.json`, lists the stored findings, and prints the
dashboard summary. Set `API_URL` to target a different local port.

## Run tests

Docker must be running for the PostgreSQL Testcontainers integration tests.

```bash
cd backend
./mvnw verify
```

PowerShell:

```powershell
Set-Location backend
.\mvnw.cmd verify
```

`mvn test` runs the unit tests. `mvn verify` also packages the application and
runs the `PostgreSQLFlowIT` integration suite. When Docker is unavailable,
Testcontainers tests are explicitly reported as skipped.

Build only the runtime image:

```bash
docker build -t vulnflow-backend:local backend
```

## API

All list endpoints use Spring pagination parameters such as `page`, `size`, and
`sort`; page size is capped at 100.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/assets` | Create an asset |
| `GET` | `/api/v1/assets` | List assets |
| `GET` | `/api/v1/assets/{id}` | Get an asset |
| `POST` | `/api/v1/scans/trivy` | Ingest a Trivy JSON report |
| `GET` | `/api/v1/scans` | List scans |
| `GET` | `/api/v1/scans/{id}` | Get a scan |
| `GET` | `/api/v1/findings` | Filter and list findings |
| `GET` | `/api/v1/findings/{id}` | Get a finding |
| `PATCH` | `/api/v1/findings/{id}/status` | Change finding workflow status |
| `GET` | `/api/v1/dashboard/summary` | Get aggregate counts |
| `GET` | `/actuator/health` | Readiness/health status |

Finding filters can be combined:

```text
GET /api/v1/findings?severity=CRITICAL
GET /api/v1/findings?status=OPEN
GET /api/v1/findings?assetId={uuid}
GET /api/v1/findings?knownExploited=true
```

### Example ingestion

```bash
asset_response=$(curl --fail --silent \
  -X POST http://localhost:8080/api/v1/assets \
  -H "Content-Type: application/json" \
  -d '{"name":"demo","type":"CONTAINER_IMAGE","externalReference":"demo:1.0"}')

# Copy the UUID from asset_response into ASSET_ID.
curl --fail \
  -X POST "http://localhost:8080/api/v1/scans/trivy?assetId=${ASSET_ID}" \
  -F "file=@sample-data/trivy-small.json;type=application/json"
```

Example response:

```json
{
  "scanId": "7fc9b7e1-b1d7-4b86-a360-31acbf6a5ab5",
  "assetId": "5e26984f-76e9-40c1-a5cd-96397e542deb",
  "status": "COMPLETED",
  "findingsImported": 1,
  "criticalFindings": 0,
  "highFindings": 1,
  "duplicate": false
}
```

Re-uploading identical bytes for the same asset returns the original scan with
`duplicate: true`. Deduplication is enforced by a database unique constraint on
`(asset_id, content_hash)`, not only by an application-level check.

## Risk calculation

The initial deterministic score is:

| Severity | Base score |
|---|---:|
| `UNKNOWN` | 0 |
| `LOW` | 20 |
| `MEDIUM` | 40 |
| `HIGH` | 70 |
| `CRITICAL` | 90 |

Known-exploited findings receive 10 additional points, capped at 100.
`knownExploited` remains `false` until CISA KEV correlation is implemented.

## Design decisions

- A feature-oriented modular monolith avoids premature distributed systems
  while preserving replaceable ingestion boundaries.
- PostgreSQL is the local source of truth; Flyway owns schema changes and
  Hibernate uses `ddl-auto=validate`.
- A scan row is persisted before parsing, so malformed reports remain auditable
  as `FAILED`.
- Finding persistence and scan completion share a transaction.
- UUIDs are created in the application, keeping tests and future event payloads
  database-independent.
- Logs are structured, lifecycle messages are in Spanish, and uploaded report
  bodies are never logged.

See [architecture](docs/architecture.md), [security](docs/security.md), and the
[architecture decisions](docs/decisions/).

## Terraform

`infrastructure/aws` currently defines no AWS resources. It can be formatted
and validated without credentials:

```bash
make terraform-format
make terraform-validate
```

The Make targets use Terraform 1.15.8 in Docker. A local CLI can instead run:

```bash
cd infrastructure/aws
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
```

**Do not run `terraform apply` against AWS until the future resources, IAM,
retention, alarms, and estimated costs have been reviewed.**

## Cost strategy

VulnFlow follows this operating model:

```text
Permanent local development
+
Temporary and reproducible AWS deployment
+
terraform destroy after demonstrations
```

The future design favors request-based serverless services, bounded log and S3
retention, budget alarms, conservative concurrency, and no NAT Gateway, load
balancer, EC2, ECS, RDS, or OpenSearch. Cost estimates and teardown verification
are release gates before any cloud demonstration.

## Current limitations

- Ingestion is synchronous and supports Trivy vulnerability JSON only.
- Syft SBOM ingestion, queues, retries, DLQ handling, CISA KEV correlation,
  alerting, authentication, authorization, and a dashboard UI are not yet
  implemented.
- The basic API intentionally has no authentication and must not be exposed to
  an untrusted network.
- Findings are scan snapshots; cross-scan lifecycle reconciliation is pending.
- Terraform contains no deployable AWS resources.

## Roadmap

1. Stabilize the local event contract and add Syft SBOM ingestion.
2. Introduce an asynchronous local adapter and idempotent retry semantics.
3. Add authentication and authorization after the basic API contract is stable.
4. Implement and cost-review S3, SQS/DLQ, and Java Lambda modules.
5. Add DynamoDB access patterns, query Lambda, and API Gateway.
6. Add CISA KEV enrichment, SNS alerts, EventBridge schedules, and CloudWatch
   observability.
7. Build a small dashboard and a time-boxed AWS demonstration runbook.

More detail is in [the AWS roadmap](docs/aws-roadmap.md).

