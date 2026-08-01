# VPS deployment

The repository `deploy/` directory is the versioned, runtime-only production
bundle for VulnFlow 0.4.6. GitHub Actions builds and publishes the backend and
agent images. The VPS only pulls immutable commit-SHA images and runs them with
Docker Compose; it does not clone source code or build Maven projects.

## Runtime layout

The configured `VPS_DEPLOY_PATH` has this layout:

```text
<VPS_DEPLOY_PATH>/
â”œâ”€â”€ deploy/                    # synchronized from this repository
â”œâ”€â”€ runtime/
â”‚   â”œâ”€â”€ .env.prod             # VPS-only secrets and operational settings
â”‚   â””â”€â”€ targets.yml           # VPS-only agent targets
â”œâ”€â”€ .release.env              # current immutable image references
â”œâ”€â”€ .release.previous.env     # previous release for rollback
â””â”€â”€ .deploy.lock              # host-side deployment serialization
```

The workflow synchronizes only `deploy/` and the non-secret candidate release
manifest. It never overwrites `runtime/.env.prod` or `runtime/targets.yml`.
The optional `runtime/aws/` directory is also VPS-owned and never synchronized
from GitHub Actions.

## One-time VPS preparation

Use a dedicated non-root deploy user. It needs SSH access, ownership of the
deployment directory, and narrowly controlled access to Docker. Membership of
the Docker group is effectively root-equivalent; use a rootless Docker daemon
or a restricted command wrapper where the host supports it.

Install Docker Engine, Docker Compose v2, `flock`, `realpath`, OpenSSH, and
`rsync`. Create `VPS_DEPLOY_PATH` with the deploy user as owner. Transfer
`deploy/.env.prod.example` and `agent/targets.example.yml` from a trusted
checkout to temporary VPS files, then install them without putting secrets in
shell history:

```bash
install -d -m 750 /srv/vulnflow/runtime
install -m 600 /tmp/vulnflow.env.prod.example /srv/vulnflow/runtime/.env.prod
install -m 644 /tmp/vulnflow.targets.example.yml /srv/vulnflow/runtime/targets.yml
```

Edit both files directly on the VPS. Populate every blank required value and make
`VULNFLOW_AGENT_TARGETS_FILE` match the absolute VPS path. If production already
has Docker volumes, set the three explicit volume-name variables to those exact
names before the first run. The deployment never executes `down`, `down -v`,
volume removal, or image pruning.

The targets file contains no credentials and must be readable by the non-root
agent UID through the read-only bind mount. Keep `runtime/.env.prod` at mode
`600`; do not apply that secret-file mode to `runtime/targets.yml`.

The example runs PostgreSQL, backend, and agent as separate containers on one
private Compose network. PostgreSQL has no published host port. The backend is
bound only to VPS loopback, and the agent reaches it over the private network.
The agent runs as a non-root container, mounts only its targets file and durable
data volume, and does not mount `/var/run/docker.sock`. It can therefore scan
registry-accessible images, not images that exist only in the VPS Docker daemon.
Use the documented systemd installation for that separate use case after an
explicit security review.

## GitHub production environment

Create an environment named `production`. Environment protection rules and a
required reviewer are recommended for the first deployment.

Configure `VPS_DEPLOY_ENABLED` as a repository variable so the job-level gate
can evaluate it before the production environment starts. Configure the other
values as `production` environment variables:

| Variable | Purpose |
| --- | --- |
| `VPS_DEPLOY_ENABLED` | Must equal `true` before the deploy job can run |
| `VPS_HOST` | DNS name or IP of the VPS |
| `VPS_PORT` | SSH port |
| `VPS_USER` | Dedicated non-root deploy user |
| `VPS_DEPLOY_PATH` | Absolute VulnFlow directory, for example `/srv/vulnflow` |
| `GHCR_USERNAME` | Optional GHCR user for private packages |

Configure these environment secrets:

| Secret | Purpose |
| --- | --- |
| `VPS_SSH_PRIVATE_KEY` | Private key dedicated to the deploy user |
| `VPS_SSH_KNOWN_HOSTS` | Pinned host-key line for the configured host and port |
| `GHCR_TOKEN` | Optional read-only package token for private GHCR images |

Obtain the SSH public host key through a trusted channel and verify its
fingerprint with the VPS provider or an existing trusted session. Store the
complete known-hosts line. For a non-default port its host field must use
`[host]:port`. The workflow uses `StrictHostKeyChecking=yes`; it never trusts
the result of an unverified `ssh-keyscan` call.

The API key and PostgreSQL password stay only in `runtime/.env.prod`. They are
not GitHub secrets, release-manifest values, command-line arguments, or workflow
outputs. If GHCR packages are public or the deploy user is already authenticated
securely, omit both optional GHCR settings.

## Release flow

On a pull request, CI runs backend `mvn verify`, confirms that Failsafe executed
non-skipped PostgreSQL tests, runs agent `mvn verify`, and builds both Docker
images. No package is published and no deployment job is eligible.

On a push to `main`, verification must pass before both images are published as:

```text
ghcr.io/<owner>/vulnflow-backend:<40-character-commit-sha>
ghcr.io/<owner>/vulnflow-agent:<40-character-commit-sha>
```

When `VPS_DEPLOY_ENABLED=true`, the production job pins the SSH host key,
synchronizes this directory, uploads a three-line immutable release manifest,
and invokes `deploy.sh`. A host `flock` and GitHub concurrency group prevent two
production deployments from running simultaneously.

`deploy.sh` validates Compose, pulls only the two candidate images, and runs
`docker compose up -d --remove-orphans`. The normal Spring Boot startup runs
Flyway before the backend becomes healthy. Migrations are not a separate shell
step and are never rolled back automatically.

## Health check and rollback

The health gate waits up to 150 seconds for:

- PostgreSQL container health to be `healthy`;
- backend container health and `/actuator/health` to report `UP`;
- the agent container to remain `running` across an additional stability wait.

If deployment fails, the script prints bounded service status and the latest
100 log lines. It preserves the failed manifest, restores the previous image
manifest, runs Compose again, and repeats the health check. The workflow still
fails after a successful rollback so the release cannot appear successful.
The first deployment has no automatic rollback target.

Rollback covers containers only. A Flyway migration may already have committed,
so every production migration must remain backward-compatible with the previous
application release. Back up PostgreSQL before a release containing a risky
data migration and define a migration-specific recovery procedure.

## Optional AWS mode

The normal Compose file always starts `SPRING_PROFILES_ACTIVE=prod`. AWS mode
is a separate override selected by the exact VPS-only setting
`VULNFLOW_AWS_MODE=true`. The deploy and health scripts include
`docker-compose.aws.yml` only when that setting is present; the repository,
CI, and current VPS default remain local.

AWS mode also requires the S3 bucket, SQS URL, DynamoDB table, and a read-only
`runtime/aws` mount containing the Roles Anywhere certificate, private key, and
AWS profile generated by `prepare-aws-runtime.sh`. The backend health indicator
rejects persistent basic credentials and reports healthy only when the AWS SDK
can resolve temporary session credentials.

Follow [AWS mode deployment](aws-mode-deployment.md) for the certificate
ceremony, preflight, activation, independent validation, and rollback. Image
rollback does not revert a manual `.env.prod` mode switch, so the pre-AWS
runtime environment backup in that runbook is mandatory.

## Manual emergency operations

All commands below run on the VPS as the deploy user from `VPS_DEPLOY_PATH`.
They intentionally use both environment files and never use `down -v`.

Inspect status and bounded logs:

```bash
docker compose --env-file runtime/.env.prod --env-file .release.env \
  -f deploy/docker-compose.prod.yml ps
docker compose --env-file runtime/.env.prod --env-file .release.env \
  -f deploy/docker-compose.prod.yml logs --tail 100 backend agent postgres
```

Restore the recorded previous release:

```bash
install -m 600 .release.previous.env .release.env
docker compose --env-file runtime/.env.prod --env-file .release.env \
  -f deploy/docker-compose.prod.yml pull backend agent
docker compose --env-file runtime/.env.prod --env-file .release.env \
  -f deploy/docker-compose.prod.yml up -d --remove-orphans
deploy/health-check.sh runtime/.env.prod .release.env
```

Deploy a specific known-good SHA by creating `.release.next.env` with the exact
backend image, agent image, and the same 40-character SHA, then run:

```bash
deploy/deploy.sh .release.next.env
```

Do not use a SHA unless both packages exist and were produced by the same
successful pipeline. Do not edit `.release.env` while a deployment is running.

To pause automatic deployment, set `VPS_DEPLOY_ENABLED=false` in the production
environment. Verification and image publication continue. GitHub administrators
can also disable the workflow or add an environment approval hold. Re-enable
only after the incident or maintenance window is closed.

If GitHub Actions is unavailable after images were published, perform the
specific-SHA procedure above over a trusted administrative session. If images
were not published, do not build ad hoc artifacts on the VPS; restore the last
known-good release and wait for CI or publish through an audited manual process.

## Reverse proxy and TLS

`deploy/nginx/vulnflow.conf` is a host-level Nginx starting point. Replace the
domain and certificate paths, validate with `nginx -t`, and provision TLS with
the host's established ACME process before enabling it. It proxies to loopback,
permits only the public health actuator endpoint, and allows multipart overhead
above the backend 10 MiB limit. Firewall rules should expose only SSH, HTTP, and
HTTPS; never publish PostgreSQL.
