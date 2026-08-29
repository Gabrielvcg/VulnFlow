# On-demand scan operations

## Safe rollout

Keep `VULNFLOW_UI_SCANS_ENABLED=false` and `VULNFLOW_AGENT_COMMANDS_ENABLED=false` for the first deployment. Verify the Roles Anywhere certificate, backend and web health, temporary credential health, Agent heartbeat, free disk, filesystem outbox, PostgreSQL publication outbox, SQS, Lambda, and DynamoDB before enabling one allowlisted target.

The Agent remains outbound-only. It polls for one command, receives a leased claim and fencing token, runs the existing 15-minute Trivy path, persists the report to its durable outbox, and uploads with the optional request identity. Scheduled `targets.yml` cycles remain available.

## Guardrails

- one active request per user and one Agent execution at a time;
- five requests per hour and twenty per day per user;
- queue capacity of 25 and ten-minute target cooldown;
- 30-minute expiry before claim;
- two abandoned-claim recoveries;
- rejection while the Agent is offline, below its disk margin, or at the 1 GiB outbox limit;
- reports limited to 10 MiB and uploaded-item retention limited to 24 hours.

## Recovery

An expired claim returns to `REQUESTED` with a new token until its recovery budget is exhausted. An obsolete token cannot start, heartbeat, upload, complete, or fail the request. Failed SQS publication may be retried by an admin from the PostgreSQL outbox. SQS DLQ redrive remains a reviewed AWS runbook operation and is intentionally absent from the console.

## Rollback

All schema changes are additive. Roll back by disabling UI, scan, command, and SQS telemetry flags and deploying the previous immutable three-image manifest. The local worker and existing API-key uploads remain valid. Do not remove the new tables during an image rollback.
