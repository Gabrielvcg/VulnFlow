# Ingestion event version 1

## Contract

```json
{"eventVersion":"1","eventId":"00000000-0000-0000-0000-000000000001","scanId":"00000000-0000-0000-0000-000000000002","assetId":"00000000-0000-0000-0000-000000000003","payloadKey":"reports/2026/report.json","contentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","scanner":"TRIVY","createdAt":"2026-07-31T10:15:30Z","correlationId":"00000000-0000-0000-0000-000000000004"}
```

`IngestionEventV1` is immutable. `IngestionEventJsonCodec` fixes the property order above, emits timestamps as ISO-8601 UTC text, rejects unknown properties, validates every required value, and rejects any `eventVersion` other than `1`.

| Field | Rule | Purpose |
|---|---|---|
| `eventVersion` | exactly `1` | Selects contract semantics. |
| `eventId` | UUID | Idempotency key for result persistence. It is stable across SQS redeliveries. |
| `scanId` | UUID | Domain scan identity. |
| `assetId` | UUID | Domain asset identity and ownership check. |
| `payloadKey` | safe logical key, at most 1,024 characters | Locates the report through `ReportStorage`; it is not a local physical path. |
| `contentHash` | 64 lowercase hexadecimal characters | Expected report SHA-256. |
| `scanner` | `TRIVY` | Scanner discriminator for V1. |
| `createdAt` | ISO-8601 instant | Producer timestamp, not a retry timestamp. |
| `correlationId` | UUID | Cross-adapter diagnostics without request content. |

The message contains no API key, credential, report bytes, uploaded filename, receipt handle, bucket name, queue URL, database claim token, or physical path. SQS message attributes repeat only `eventVersion`, `eventId`, and `correlationId` for safe routing and diagnostics.

## Compatibility

V1 is immutable after release. Additive fields are not added silently because V1 readers deliberately reject unknown input. A semantic or structural change requires `IngestionEventV2`, a new codec branch, producer rollout after consumers can read both versions, and explicit retirement evidence before V1 is removed. Redelivery never changes `eventId`.

## Idempotency and terminal results

The DynamoDB adapter binds `eventId` to `scanId`, `assetId`, `contentHash`, `scanner`, and normalized
finding count. A completed or safely failed duplicate is success. A different identity or content under
the same event ID is a permanent conflict. An incomplete `WRITING` result resumes deterministic finding
keys and remains invisible to result queries until the event and scan commit markers change atomically.
