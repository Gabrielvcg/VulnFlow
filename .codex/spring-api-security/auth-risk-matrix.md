# Authentication and risk matrix

| Surface | Authentication | Authorization scope | Data source / side effect | Primary risk | Current control |
|---|---|---|---|---|---|
| `POST /api/v1/scans/trivy` local | `X-API-Key` | API client | Filesystem + PostgreSQL local job | resource exhaustion, malicious JSON | 10 MB bounds, JSON media type, internal paths, async parser |
| `POST /api/v1/scans/trivy` AWS | `X-API-Key` | API client | S3 + PostgreSQL outbox | orphan/duplicate window, resource exhaustion | validated config, internal key, compensation, durable outbox, bounded upload |
| `GET /api/v1/scans/{id}` AWS | `X-API-Key` | API client | DynamoDB/PG pending projection | broad read access / ID enumeration | UUID IDs, API key, DTO projection; per-tenant auth is future work |
| `GET /api/v1/scans/{id}/findings` AWS | `X-API-Key` | API client | DynamoDB paginated read | large reads / cursor abuse | size 1–100, cursor length/shape validation, API key |
| SQS Lambda handler | AWS service invocation policy (future) | source queue mapping | S3 read + DynamoDB write | forged/replayed event, poison batch | strict V1 contract, prefix validation, SHA-256, event identity fencing, partial failures |
| AWS outbox publisher | application scheduler | internal only | S3 HEAD + SQS send | duplicate publication / stale worker | short `SKIP LOCKED` claim, token fence, bounded retries, stale recovery |
| SQS DLQ redrive | future AWS operator | account IAM | queue-to-queue redrive | repeated poison/duplicate messages | manual runbook, prechecks, event idempotency; no automatic endpoint |
| VPS deployment | protected GitHub environment | deploy operator | local `prod` containers only | accidental AWS activation | Compose remains `prod`; no AWS profile or AWS credentials |
