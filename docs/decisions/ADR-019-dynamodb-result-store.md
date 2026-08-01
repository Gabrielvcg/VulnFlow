# ADR-019: DynamoDB as the initial AWS result store

Status: Accepted for 0.4.1.

## Decision

AWS processing results use one on-demand DynamoDB table. An event marker is stored at
`EVENT#<eventId>/META`; scan metadata is stored at `SCAN#<scanId>/META`; normalized findings use
`SCAN#<scanId>/FINDING#<zero-padded-index>`. Completed scan metadata is projected into `gsi1` under
`ASSET#<assetId>` for future asset listings.

Large reports are staged with `status=WRITING`. Findings are written in deterministic batches of 25,
then one `TransactWriteItems` operation changes both event and scan markers to `COMPLETED`. Readers
return findings only after the scan commit marker is complete. A retry overwrites the same finding keys.
This avoids the 400 KB item limit and the 100-operation transaction limit without exposing partial data.

The table uses server-side encryption, on-demand billing, and configurable point-in-time recovery. No
TTL is configured because scan results are not currently safe to expire automatically.

## Consequences

There is no relational join or cross-scan transaction. Dashboard access patterns beyond scan lookup,
finding pagination, severity summary, and the prepared asset index need an explicit design before use.
Staged finding writes consume capacity even when the final transaction must be retried.
