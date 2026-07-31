# VulnFlow

VulnFlow 0.1.1 is a local-first Spring Boot platform for receiving, normalizing,
storing, and querying Trivy vulnerability reports. The current implementation is
a synchronous modular monolith backed by PostgreSQL. No AWS resource, frontend,
queue, worker, Syft integration, or cross-scan reconciliation exists yet.

## Current architecture

```text
Trivy JSON
    |
    | multipart/form-data + X-API-Key
    v
Spring Boot
    |-- validate size, media type, and Trivy structure
    |-- hash and serialize registration by asset/hash
    |-- parse and calculate risk
    |-- persist findings and completion atomically
    v
PostgreSQL + Flyway
```

The application uses Java 17, Spring Boot 3.5, Spring MVC, Spring Security,
Validation, Data JPA, Actuator, PostgreSQL 16, Flyway, Jackson, springdoc,
Maven, JUnit, Mockito, Testcontainers, Docker Compose, and a validation-only
Terraform skeleton.

## Start locally

Requirements: Docker Desktop or Docker Engine with Compose v2.

```bash
cp .env.example .env
docker compose up --build -d
docker compose ps
curl http://localhost:8080/actuator/health
```

PowerShell:

```powershell
Copy-Item .env.example .env
docker compose up --build -d
docker compose ps
Invoke-RestMethod http://localhost:8080/actuator/health
```

Compose binds both services to `127.0.0.1` by default. PostgreSQL is not
published on all network interfaces. Values in `.env.example` are local-only
examples, not production secrets.

Useful local URLs:

- API: `http://localhost:8080/api/v1`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Health: `http://localhost:8080/actuator/health`

Health and Swagger are public for local developer use. Every `/api/v1/**`
request requires:

```http
X-API-Key: value-from-VULNFLOW_API_KEY
```

The API key authentication is provisional machine-to-machine protection. It
does not provide users, roles, ownership, or tenant isolation. A future human
interface must use OIDC or JWT with explicit authorization.

## Run the demo

The script reads the API key from the environment and never embeds or logs it:

```bash
export VULNFLOW_API_KEY=local-development-only-api-key
sh scripts/demo.sh
```

PowerShell with Git Bash or another POSIX shell:

```powershell
$env:VULNFLOW_API_KEY = "local-development-only-api-key"
sh scripts/demo.sh
```

The demo creates an asset, uploads `sample-data/trivy-multiple.json`, lists its
findings, and reads the dashboard summary.

## API

All paginated endpoints accept `page`, `size`, and `sort`, cap page size at 100,
and use stable default ordering:

- Assets: `createdAt DESC, id DESC`
- Scans: `receivedAt DESC, id DESC`
- Findings: `detectedAt DESC, id DESC`

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/assets` | Create an asset |
| `GET` | `/api/v1/assets` | List assets |
| `GET` | `/api/v1/assets/{id}` | Get an asset |
| `POST` | `/api/v1/scans/trivy` | Ingest or retry a Trivy report |
| `GET` | `/api/v1/scans` | List scans |
| `GET` | `/api/v1/scans/{id}` | Get a scan |
| `GET` | `/api/v1/findings` | Filter finding summaries |
| `GET` | `/api/v1/findings/{id}` | Get a finding with full description |
| `PATCH` | `/api/v1/findings/{id}/status` | Change finding workflow status |
| `GET` | `/api/v1/dashboard/summary` | Read aggregate counts |
| `GET` | `/actuator/health` | Public health status |

Finding filters are `severity`, `status`, `assetId`, and `knownExploited`.
Finding lists omit descriptions; detail responses include the bounded full
description.

### Ingestion example

```bash
asset_response=$(curl --fail --silent \
  -X POST http://localhost:8080/api/v1/assets \
  -H "X-API-Key: ${VULNFLOW_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"name":"demo","type":"CONTAINER_IMAGE","externalReference":"demo:1.0"}')

curl --fail \
  -X POST "http://localhost:8080/api/v1/scans/trivy?assetId=${ASSET_ID}" \
  -H "X-API-Key: ${VULNFLOW_API_KEY}" \
  -F "file=@sample-data/trivy-small.json;type=application/json"
```

An imported response includes both the number created by this request and the
total currently associated with the scan:

```json
{
  "scanId": "7fc9b7e1-b1d7-4b86-a360-31acbf6a5ab5",
  "assetId": "5e26984f-76e9-40c1-a5cd-96397e542deb",
  "status": "COMPLETED",
  "outcome": "IMPORTED",
  "findingsImported": 1,
  "totalFindings": 1,
  "criticalFindings": 0,
  "highFindings": 1,
  "duplicate": false
}
```

## Scan lifecycle and deduplication

The database keeps `UNIQUE (asset_id, content_hash)`. The hash covers the exact
uploaded bytes; filename does not affect deduplication.

```text
new content       -> PROCESSING -> COMPLETED
                              \-> FAILED
FAILED retry      -> PROCESSING -> COMPLETED or FAILED
COMPLETED repeat  -> DUPLICATE, HTTP 200, findingsImported=0
PROCESSING repeat -> ALREADY_PROCESSING, HTTP 202, no second processor
```

`ScanRegistrationService.registerProcessing()` runs in `REQUIRES_NEW`, uses
PostgreSQL `ON CONFLICT` and a pessimistic row lock, and safely claims a new or
failed scan. A retry reuses the `scanId` and removes incompatible old findings.

`IngestionPersistenceService.complete()` runs in another `REQUIRES_NEW`
transaction. All findings and the transition to `COMPLETED` commit together or
roll back together.

`ScanFailureService.markFailed()` runs in a third `REQUIRES_NEW` transaction.
Only parsing and transactional persistence errors call it. Failures while
building or serializing the later response cannot change a completed scan to
`FAILED`. If failure recording also fails, the original exception is preserved
and the second error is attached as suppressed.

`ScanRecoveryService.recoverStaleProcessingScans()` is an idempotent local
operation prepared for a future scheduler. It changes scans older than
`VULNFLOW_PROCESSING_TIMEOUT` to `FAILED` with a generic technical reason. No
permanent scheduled job is enabled in 0.1.1.

## Trivy validation and limits

A report must have an object root and an explicit array `Results`. Empty
`Results` and `Vulnerabilities: null` are valid. Null/non-array `Results`,
non-object results, non-array vulnerabilities, non-object vulnerabilities, and
entries without `VulnerabilityID` or `PkgName` are rejected with `422`.

Limits:

- Multipart request/file: `VULNFLOW_MAX_FILE_SIZE`, default `10MB`
- Finding description: `VULNFLOW_MAX_DESCRIPTION_LENGTH`, default 8000
- Processing timeout: `VULNFLOW_PROCESSING_TIMEOUT`, default `15m`

Descriptions and non-critical display fields may be bounded. Oversized
`VulnerabilityID` and package identifiers are rejected rather than silently
truncated. Uploaded filenames are stored only as bounded metadata after path
components and control characters are removed; they are never used as a write
path.

## Errors

The API uses a common error DTO and does not return stack traces:

- Unreadable JSON, missing fields/parts, invalid UUID/enums: `400`
- Invalid API key: `401`
- Missing asset: `404`
- Unsupported media type: `415`
- Oversized report: `413`
- Invalid Trivy report: `422`
- Unexpected internal error: `500`

## Tests

Docker must be running for PostgreSQL Testcontainers:

```powershell
Set-Location backend
.\mvnw.cmd verify
```

The suite covers parsing, transaction failure, retry, concurrent upload,
completed and processing deduplication, separate assets, filename independence,
API-key enforcement, client errors, description projection, Flyway, recovery,
and end-to-end PostgreSQL behavior.

## Terraform

`infrastructure/aws` defines zero AWS resources. Validation does not require AWS
credentials:

```bash
make terraform-format
make terraform-validate
```

Do not run `terraform apply`. AWS, S3, SQS, Lambda, DynamoDB, EventBridge, SNS,
and cloud authentication are intentionally outside version 0.1.1.

## Current limitations

- Ingestion remains synchronous and supports Trivy vulnerability JSON only.
- The full uploaded JSON is materialized in memory after the size gate.
- API-key authentication has no per-client identity, roles, ownership, rotation
  protocol, throttling, or TLS termination.
- Swagger is intentionally public on the localhost-only development binding.
- No scheduler invokes stale-scan recovery automatically.
- Findings are snapshots; reconciliation between scans is not implemented.
- Terraform contains no deployable resources and no AWS contact is required.

See [architecture](docs/architecture.md), [security](docs/security.md), and
[architecture decisions](docs/decisions/).
