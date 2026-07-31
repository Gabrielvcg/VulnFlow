# ADR-013: GitHub Actions delivery to a runtime-only VPS

## Status

Accepted for VulnFlow 0.3.1.

## Context

VulnFlow 0.3.0 could build backend and agent images but had no production
delivery path. The first operational target is one VPS running PostgreSQL,
backend, and agent. The repository must retain its local-first behavior, avoid
AWS, preserve PostgreSQL reports and agent-outbox data, and prevent feature
branches or overlapping jobs from changing production.

The deployment references in `micsu`, `BenchLab`, `secureservices`, and
`realityCheck` all build in CI and update runtime-only hosts. `realityCheck` has
the most recent operational scripting and recovery documentation. `BenchLab`
has the clearest multi-image SHA publication model. The older projects accept a
newly discovered host key and none provides a health-triggered automatic
rollback, so those parts cannot be copied unchanged.

## Decision

Use one GitHub Actions workflow with three dependent jobs:

1. Verify both Maven projects, require executed PostgreSQL Failsafe reports,
   and build both Docker images on every pull request and `main` push.
2. On `main` only, publish backend and agent images to GHCR with the exact commit
   SHA. Only this job receives `packages: write`.
3. When the protected production environment explicitly enables deployment,
   connect as a non-root deploy user using a pinned host key, synchronize only
   the deployment bundle, and invoke the host deployment script.

Run all three application components as separate Compose services. PostgreSQL,
backend reports, and agent data use stable explicitly named volumes. PostgreSQL
is private and the backend publishes only a loopback port for host Nginx. The
agent is non-root, has a read-only targets mount, and does not receive the Docker
socket; it scans registry-accessible targets only.

Keep runtime secrets and target configuration under a VPS-only `runtime/`
directory. A release manifest contains only immutable image references and the
commit SHA. GitHub concurrency plus `flock` serialize deployment. Compose uses
pull and `up -d` without `down`, volume deletion, or pruning.

Before an update, retain the current release manifest. Require PostgreSQL and
backend health, an `UP` actuator response, and a stable running agent. On
failure, restore the preceding image manifest and repeat Compose and health
checks. Preserve bounded diagnostics and the failed manifest.

Flyway remains part of normal backend startup. Automatic rollback applies only
to containers and image references; it never claims to reverse committed schema
or data migrations. Production migrations must therefore be compatible with
the preceding release and use separate backup/recovery planning when risky.

## Consequences

- A reviewed `main` commit maps unambiguously to both running images.
- Pull requests cannot deploy, and production can be paused without disabling
  verification or publication.
- Secrets do not move from the VPS into the workflow, while private GHCR access
  can use an optional read-only token.
- Existing persistent volumes can be adopted by exact name and are not removed
  during release or rollback.
- The first deployment has no rollback target.
- Conventional Docker-group access remains privileged and should move to
  rootless Docker or a constrained command boundary where practical.
- Actions are pinned to the immutable commits of documented releases. Automated
  dependency updates and image signing/attestation are future supply-chain
  hardening.
- The containerized agent cannot scan images present only in the host daemon.
  That use case retains the manually managed systemd option.

## Alternatives rejected

- Building on the VPS: expands the host toolchain and breaks artifact parity.
- Deploying `latest`: loses provenance and makes rollback ambiguous.
- Copying credentials from another repository: violates secret isolation.
- Trust-on-first-use `ssh-keyscan`: does not authenticate the first connection.
- Mounting `/var/run/docker.sock`: grants excessive host control for the default
  registry-target use case.
- `docker compose down -v` or broad pruning: can destroy evidence and rollback
  material.
- Terraform or AWS services: outside the 0.3.1 VPS scope.
