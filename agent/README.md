# VulnFlow Agent 0.4.1

The VulnFlow Agent is a standalone Java 17 process for explicitly configured
Linux hosts. It runs Trivy image scans, stores reports in a durable local
outbox, resolves the corresponding VulnFlow asset, and uploads the report to
the asynchronous ingestion API. It has no compile-time dependency on the
backend and does not expose an HTTP server.

## Requirements

- Java 17.
- Trivy available as a host executable. Choose the official package repository
  or signed release method for the target Linux distribution from the
  [Trivy installation guide](https://trivy.dev/latest/getting-started/installation/),
  then verify it with `trivy --version`.
- Network access to the configured VulnFlow API and to any image registry used
  by the configured targets.

Host-process execution is recommended when local Docker image access matters.
The agent never scans the host inventory automatically and never invokes a
shell. Every image reference must appear in the targets YAML file.

## Build and configure

```bash
./mvnw verify
cp targets.example.yml targets.yml
```

Required configuration:

```bash
export VULNFLOW_API_URL=http://127.0.0.1:8080/
export VULNFLOW_API_KEY='read-from-a-secret-file-or-manager'
export VULNFLOW_AGENT_ID=vps-01
export VULNFLOW_TARGETS_FILE="$PWD/targets.yml"
```

Operational variables and defaults:

| Variable | Default |
| --- | --- |
| `VULNFLOW_SCAN_INTERVAL` | `1h` |
| `VULNFLOW_TRIVY_PATH` | `trivy` |
| `VULNFLOW_AGENT_DATA_DIR` | `./data/agent` |
| `VULNFLOW_AGENT_TEMP_DIR` | `<data-dir>/tmp` |
| `VULNFLOW_AGENT_MAX_CONCURRENT_SCANS` | `1` |
| `VULNFLOW_AGENT_UPLOAD_RETRY_INTERVAL` | `30s` |
| `VULNFLOW_TRIVY_TIMEOUT` | `15m` |
| `VULNFLOW_AGENT_MAX_REPORT_SIZE` | `10MB` |
| `VULNFLOW_AGENT_MAX_OUTBOX_BYTES` | `1GB` |
| `VULNFLOW_AGENT_MAX_OUTBOX_ITEMS` | `1000` |
| `VULNFLOW_AGENT_UPLOADED_RETENTION` | `7d` |
| `VULNFLOW_AGENT_HTTP_CONNECT_TIMEOUT` | `10s` |
| `VULNFLOW_AGENT_HTTP_REQUEST_TIMEOUT` | `2m` |
| `VULNFLOW_AGENT_SHUTDOWN_TIMEOUT` | `30s` |

Durations must be positive and accept `ms`, `s`, `m`, `h`, `d`, or ISO-8601.
Sizes accept `B`, `KB`, `MB`, or `GB`.

## Commands

```bash
java -jar target/vulnflow-agent-0.4.1.jar --check
java -jar target/vulnflow-agent-0.4.1.jar --once
java -jar target/vulnflow-agent-0.4.1.jar --status
java -jar target/vulnflow-agent-0.4.1.jar
```

`--check` validates configuration and prints a secret-free effective view.
`--once` performs one scan, upload, and cleanup cycle. With no option, the
scheduled process runs until SIGTERM. `--status` reads local state and outbox
counters without creating a permanent server.

## Durable outbox

Each scan becomes `data/outbox/items/<random-uuid>/report.json` plus atomically
updated `metadata.json`. A new item may initially have no `assetId` when the
backend is unavailable; the upload cycle resolves and persists it before the
first HTTP upload. This preserves the requirement that scanning works offline.

`PENDING`, `RETRY_WAIT`, and `DEAD_LETTER` reports are never removed by
retention. `UPLOADED` reports remain available for the configured retention
period. Capacity exhaustion rejects a new report and logs the condition rather
than deleting pending work. SHA-256 is checked again before every upload.

## systemd

Install the shaded JAR under `/opt/vulnflow-agent`, create a dedicated
`vulnflow-agent` user, copy the unit file, store the real environment file at
`/etc/vulnflow-agent/vulnflow-agent.env`, and put targets at
`/etc/vulnflow-agent/targets.yml`.

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now vulnflow-agent
sudo systemctl status vulnflow-agent
sudo journalctl -u vulnflow-agent
```

The supplied unit is non-root, restricts filesystem access, and grants writes
only beneath `/var/lib/vulnflow-agent`. Trivy's cache is directed beneath that
same state directory. Installation is deliberately manual.

## Docker

The optional root-level `docker-compose.agent.yml` builds a non-root agent with
Trivy 0.70.0 pinned by tag and image digest, a persistent outbox volume, and a
read-only targets mount. It does not mount `/var/run/docker.sock`.

```bash
VULNFLOW_API_KEY='configured-value' \
docker compose -f docker-compose.agent.yml up --build
```

Without the Docker socket, a containerized agent can scan registry-accessible
images but cannot inspect images that exist only in the host daemon. Use the
host-process installation for that case.

See [the complete agent guide](../docs/agent.md) for lifecycle, security,
failure recovery, resource limits, and demo instructions.
