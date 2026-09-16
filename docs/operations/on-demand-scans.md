# On-demand scan operations

## Safe rollout

Keep `VULNFLOW_UI_SCANS_ENABLED=false` and `VULNFLOW_AGENT_COMMANDS_ENABLED=false` for the first deployment. Verify the Roles Anywhere certificate, backend and web health, temporary credential health, Agent heartbeat, free disk, filesystem outbox, PostgreSQL publication outbox, SQS, Lambda, and DynamoDB before enabling one allowlisted target.

The Agent remains outbound-only. It polls for one command, receives a leased claim and fencing token, runs the existing 15-minute Trivy path, persists the report to its durable outbox, and uploads with the optional request identity. Scheduled `targets.yml` cycles remain available.

The API catalog authorizes remote scan targets. Enabling Agent commands explicitly
trusts the configured server to request container image scans; the image does not
need an entry in local `targets.yml`. That file is exclusively the recurring scan
schedule and may contain `targets: []` for command-only operation. Registering a
console target never schedules it automatically. Existing schedules are preserved.

Register an exact image reference under Targets, then choose its name and an Agent
under Scans and press Launch scan. Selecting an image alone does not launch work.
Private images require registry credentials accessible to Trivy on the selected
machine. No registry credentials are sent through the console.

`POST /api/ui/v1/scan-requests` accepts `targetId` and optional `agentId`.
`GET /api/ui/v1/scan-requests/agents` lists agent IDs, status, and online state.
Omitting `agentId` chooses an available agent at admission. The assignment persists
through lease recovery; another machine cannot claim that request. Legacy queued
requests without an assignment remain claimable. Agent groups and namespace
wildcards are not implemented: register each exact image only in the API catalog.
An Agent renews its lease every 15 seconds while Trivy is running. Claiming also
checks idle state, free disk, and outbox capacity.

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
