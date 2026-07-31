# ADR-010: Standalone VPS scanning agent

## Status

Accepted for VulnFlow 0.3.0.

## Decision

Run continuous scanning as a small Java 17 process independent of Spring Boot
and the backend build. The agent uses JDK `HttpClient`, Jackson,
`ScheduledExecutorService`, and an explicit `VulnerabilityScanner` boundary.
Only operator-configured container-image targets are scanned.

Host-process execution is the preferred deployment because it can use the
operator's Trivy and image access without granting a container the Docker
socket. A hardened non-root systemd unit and an optional non-root container are
provided.

## Consequences

Backend downtime does not stop scans because reports enter a local outbox.
Operators must manage Java, Trivy, disk capacity, target configuration, and one
agent process per data directory. The agent deliberately has no inbound server,
host discovery, Syft, frontend, or cloud dependency.
