# VulnFlow continuous scanning agent

## Purpose and trust boundary

The 0.3.1 agent completes the path from a configured Docker image to VulnFlow
findings without requiring an operator to upload every report manually:

```text
configured image -> Trivy -> local outbox -> asset resolution -> ingestion API
                 -> PostgreSQL job -> backend worker -> findings
```

It scans only YAML entries supplied by the operator. It does not enumerate the
Docker daemon, mount its socket, run as root, or execute target text through a
shell. The API key is read only from the environment and is absent from target
files, safe configuration output, logs, and metadata.

## Scheduler boundaries

- The scan cycle applies a global concurrency limit and a per-target guard.
  One failure is logged and isolated from other targets. Reports enter the
  outbox before any backend dependency is required.
- The upload cycle atomically claims ready items, validates SHA-256, resolves
  assets, uploads multipart JSON, and records the backend receipt.
- The cleanup cycle deletes only successfully uploaded reports older than the
  configured retention. It never deletes pending, retrying, uploading, or dead
  letter data.

Scheduled delays use `ScheduledExecutorService` and persisted `nextAttemptAt`;
workers do not sleep between retries. SIGTERM triggers a bounded graceful
shutdown.

## Trivy execution

Startup runs exactly `trivy --version` and aborts if the executable is missing,
times out, or exits unsuccessfully. Image scans use an argument list equivalent
to:

```text
trivy image --scanners vuln --format json --output <internal-temp-file> <reference>
```

No `sh -c`, `bash -c`, `cmd /c`, string-built `Runtime.exec`, or target-derived
path is used. stdout and stderr are drained but retained only up to 16 KiB. The
process has a configurable timeout; the resulting file must be non-empty,
bounded, and valid JSON. The volatile top-level Trivy `CreatedAt` field is
removed before storage so identical findings for an unchanged image produce a
stable backend deduplication hash. All other report data remains intact.

## Outbox durability and recovery

An item directory is created under a random UUID using same-filesystem
temporary writes and an atomic directory rename. Metadata changes use a forced
temporary file and atomic replacement. Metadata includes agent, target,
optional/resolved asset ID, timestamps, internal filename, SHA-256, bytes,
attempt count, next attempt, bounded safe error, state, and backend receipt.

The asset ID is nullable only between an offline scan and its first successful
resolution. The stable target-to-asset cache is stored separately and can be
reconstructed through `PUT /api/v1/assets/resolve` if lost.

On startup, an interrupted `UPLOADING` item moves to `RETRY_WAIT`. In-process
claiming prevents two upload cycles from sending the same item. Running two
agent processes over the same data directory is unsupported; systemd should
own one process per directory.

Response policy:

| Result | Local action |
| --- | --- |
| backend `200` or `202` | `UPLOADED` and retain report |
| `401`/`403` | hourly-or-slower retry; operator configuration error |
| upload `404` | invalidate cache, resolve once, retry upload once |
| other `4xx` | `DEAD_LETTER` |
| `5xx`, DNS, connection, read timeout | exponential `RETRY_WAIT` |

## Capacity and resource consumption

Concurrency defaults to one Trivy process. Report size defaults to 10 MiB, the
outbox to 1 GiB and 1,000 items, stdout/stderr capture to 16 KiB, and uploaded
retention to seven days. Memory use is bounded for process output and backend
responses; Jackson still builds the bounded Trivy JSON tree once to validate
and normalize it.

When capacity is reached, the new scan cannot enter the outbox. Existing work
is retained and an operational error is logged. Operators must inspect
`--status`, uploaded retention, and dead letters before increasing limits.

## Installation and operation

Install Java 17, then choose the supported repository or signed-binary method
for the host distribution from the
[official Trivy installation guide](https://trivy.dev/latest/getting-started/installation/).
Run `trivy --version`, build with `agent/mvnw verify`, configure a targets file,
and use `--check` before enabling the systemd unit. The commands and environment
table are in [the agent README](../agent/README.md). The agent never installs or
updates Trivy on the operator's behalf.

Structured JSON logs identify `agentId`, target name, local/outbox ID, asset ID,
upload attempt, and result when relevant. They intentionally omit target image
credentials, API keys, HTTP headers, complete reports, descriptions, and host
paths.

## Docker limitations

The optional image runs as a non-root UID, retains data in a named volume,
mounts targets read-only, and pins Trivy. No Docker socket is mounted. It can
pull public or explicitly authenticated registry images using Trivy-supported
credentials, but it cannot see images available only in the host daemon. A host
service is the recommended mode for that scenario.

The 0.3.1 production bundle chooses this containerized mode deliberately. The
agent is a separate Compose service and image, communicates with the backend on
the private network, reads a VPS-only targets file, and stores its outbox and
Trivy cache in a named volume. Its image is deployed by the same commit SHA as
the backend. A backend health dependency prevents normal agent startup before
Flyway and the API are ready, while the durable outbox handles later API
interruptions.

CI/CD does not grant host Docker access. If a target is available only in the
VPS daemon, keep the agent out of this container topology and use the dedicated
systemd unit after a manual installation and permission review. Do not add the
Docker socket to Compose as a convenience change: socket possession generally
allows host-equivalent control.

## Verification and demos

`scripts/agent-e2e-fake.sh` supplies a deterministic fake Trivy executable and
proves outbox, asset resolution, asynchronous backend processing, findings,
and duplicate handling without requiring Trivy or AWS. `scripts/agent-demo.sh`
does the same with an installed Trivy and `VULNFLOW_DEMO_IMAGE` (default
`alpine:3.15`). Vulnerability counts are deliberately not asserted.

## Future AWS integration

The local agent remains useful when the backend moves to AWS. The intended
transport is:

```text
Agent -> presigned S3 upload -> SQS -> Lambda
```

That phase must preserve the outbox and checksum contracts, replace API-key
handling appropriately, and use SQS receipt handles and visibility timeouts.
No AWS SDK, upload, queue, credential, or resource exists in 0.3.1.
