# ADR-019: DynamoDB as the initial AWS result store

Status: Accepted for 0.4.1.

## Decision

AWS processing results use one on-demand DynamoDB table. An event marker is stored at
`EVENT#<eventId>/META`; scan metadata is stored at `SCAN#<scanId>/META`; normalized findings use
`SCAN#<scanId>/FINDING#<zero-padded-index>`. Completed scan metadata is projected into `gsi1` under
`ASSET#<assetId>` for future asset listings.

Large reports are staged with `status=WRITING`. Findings are written in deterministic batches of 25,
then one `TransactWriteItems` operation changes both event and scan markers to `COMPLETED`. Readers
return findings only after the scan commit marker is complete. Each findings page, including a
continuation page, reads the summary with strong consistency and requires `COMPLETED`. Cursor syntax
and size are validated before storage access; blank cursors retain first-page semantics. A retry
overwrites the same finding keys.
This avoids the 400 KB item limit and the 100-operation transaction limit without exposing partial data.

The table uses server-side encryption, on-demand billing, and configurable point-in-time recovery. No
TTL is configured because scan results are not currently safe to expire automatically.

## Consequences

There is no relational join or cross-scan transaction. Dashboard access patterns beyond scan lookup,
finding pagination, severity summary, and the prepared asset index need an explicit design before use.
Staged finding writes consume capacity even when the final transaction must be retried.
Every continuation page now adds one strongly consistent metadata lookup compared with the previous
first-page-only check. This read cost preserves the completion invariant even for caller-supplied
cursors; the cursor format, query limit and strongly consistent findings query remain unchanged.

## Batch summary retry pacing

Summary BatchGet reads deduplicate IDs and use batches of at most 100 keys. Each batch retains a
three-call application attempt budget. Successful partial responses retain already returned summaries
and retry only `UnprocessedKeys`. Missing items without unprocessed keys are not retried. Strong-read
request options remain part of the original and returned key sets.

When keys remain and another attempt is available, the application requests a full-jitter delay:
uniform integer milliseconds in [0, 50] before attempt two and [0, 100] before attempt three. There is
no delay before the first attempt, after success or after exhaustion. The maximum requested wait is
150 ms per batch; separate batches have separate budgets. Zero jitter is possible, so pacing does
not guarantee recovery. These small fixed windows bound the new application wait without claiming
production-optimal tuning. Actual elapsed time can exceed the requested delays due to scheduling;
SDK/network latency and SDK exception-retry delays are outside this budget.

The synchronous caller waits during these delays. An interrupted wait restores the thread interrupt
flag and throws `TransientProcessingStoreException` without making the next client call. Existing SDK
error classification remains unchanged; this loop adds no application retries for thrown SDK errors.

AWS [BatchGetItem documentation](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_BatchGetItem.html)
recommends exponential backoff when retrying unprocessed keys from partial responses. Such responses
can arise from throughput, size or processing limits; they are distinct from thrown SDK exceptions.
Package-private wait/jitter injection permits deterministic tests without real sleeps. The public
constructor and configuration contract are unchanged. Virtual-clock recovery fixtures demonstrate
control flow, not real AWS throttling recovery or end-to-end latency.

## Finding limit rejection

The configured finding limit remains enforced before beginning a new completion or writing findings.
A new report exceeding it raises the existing permanent `InvalidReportException`, not an event identity
conflict. Lambda follows its existing `INVALID_REPORT` failure path and acknowledges only after failure
persistence returns. A transient failure writing the rejection returns that record in the batch failure
response, so it remains eligible for the existing queue retry/DLQ policy. No application retry loop is added.

Existing event identity and normalized-count consistency are checked before applying the limit. A
matching completed duplicate remains a no-op even if the configured limit was reduced later; genuine
identity/count conflicts still must not overwrite an existing result. Finalized delivery detection also
continues to avoid payload loading.

Failure persistence cannot replace a matching `WRITING` marker. If such a marker already exists, or
appears during the conditional failure transaction, the store raises a transient processing exception
instead of silently acknowledging the incomplete event. This preserves staged data and requests retry;
it does not convert staged work into a failed result or bypass the limit. A result finalized by another
writer is treated as a duplicate; a raced identity conflict remains protected. If a reduced limit keeps
blocking staged work, bounded queue retries can still reach DLQ and require operator resolution.

The regression exercises the real parser, processor, Lambda handler and DynamoDB adapter with mocked
SDK responses. Its conditional-write model demonstrates application control flow, not real DynamoDB
transactions, live concurrency or queue delivery.
