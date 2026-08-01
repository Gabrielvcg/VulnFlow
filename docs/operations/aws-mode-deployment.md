# AWS mode deployment

This is the controlled procedure for switching the existing VulnFlow VPS from
local processing to the prepared AWS ingestion path. Nothing in this document
authorizes Terraform apply, certificate creation, credential changes, or the
mode switch itself.

## Preconditions

- `main` and the deployed immutable image SHA are recorded and healthy.
- PostgreSQL has a current tested backup and the three Docker volume names are
  recorded.
- `vulnflow-admin` is a logged-in `VulnFlowTerraformOperator` Identity Center
  session accepted by `scripts/aws/assert-temporary-identity.ps1`.
- The bootstrap and application plans are newly generated from that commit,
  contain no unexpected change/destroy actions, and have recorded SHA-256
  digests.
- The state bucket has been separately applied and both Terraform roots have
  been migrated to S3 native locking.
- The application resources and optional Roles Anywhere identity have been
  applied from reviewed plans.
- The certificate ceremony in `docs/security/iam-roles-anywhere.md` is complete.
- A named owner, rollback window, residual-resource check, and destroy time are
  recorded.

## Prepare the VPS without activating AWS

Create `/opt/vulnflow/app/runtime/aws` and place only:

```text
client.pem       # public leaf certificate
client-key.pem   # private workload key
```

Run the preparation script as root with Terraform outputs:

```bash
sudo /opt/vulnflow/app/deploy/prepare-aws-runtime.sh \
  '<trust-anchor-arn>' \
  '<profile-arn>' \
  'arn:aws:iam::160172542031:role/vulnflow-demo-backend-role' \
  '/opt/vulnflow/app/runtime/aws'
```

The script writes the credential-process profile without obtaining or storing
AWS session credentials. Do not enable AWS mode if it reports any validation
failure.

## Stage the runtime switch

Back up the local runtime environment outside the synchronized `deploy/`
directory:

```bash
sudo install -m 600 runtime/.env.prod runtime/.env.prod.pre-aws
```

Set these values in `runtime/.env.prod`, preserving the database, API key,
volume, and agent values already present:

```text
VULNFLOW_AWS_MODE=true
AWS_REGION=eu-west-1
VULNFLOW_S3_BUCKET=vulnflow-demo-160172542031-eu-west-1-reports
VULNFLOW_S3_PREFIX=reports
VULNFLOW_SQS_QUEUE_URL=https://sqs.eu-west-1.amazonaws.com/160172542031/vulnflow-demo-ingestion
VULNFLOW_DYNAMODB_TABLE=vulnflow-demo-results
VULNFLOW_DYNAMODB_MAX_FINDINGS=100000
VULNFLOW_AWS_CREDENTIALS_DIRECTORY=/opt/vulnflow/app/runtime/aws
```

Validate the resolved configuration before restarting anything:

```bash
docker compose --env-file runtime/.env.prod --env-file .release.env \
  -f deploy/docker-compose.prod.yml -f deploy/docker-compose.aws.yml config --quiet
```

The resolved backend must contain `prod,aws`, the Roles Anywhere profile and
read-only credential mount, and no access-key environment variables.

## Activate and verify

Reuse the current immutable images through the normal serialized deployment:

```bash
install -m 600 .release.env .release.next.env
deploy/deploy.sh .release.next.env
```

The backend health indicator calls the configured credential provider and
remains DOWN unless it resolves temporary session credentials. Then upload one
synthetic Trivy report and verify S3 object creation, outbox publication, SQS
delivery, Lambda completion, DynamoDB query results, idempotent replay, and DLQ
behavior using identifiers only. Never log report bodies or credentials.

AWS mode changes the active query source to DynamoDB; existing PostgreSQL local
scan/finding rows and report volume are retained for rollback but are not the
AWS query source.

## Rollback

Image rollback is already automatic, but it cannot undo a manual mode change.
To return to local mode:

```bash
sudo install -m 600 runtime/.env.prod.pre-aws runtime/.env.prod
install -m 600 .release.previous.env .release.next.env
deploy/deploy.sh .release.next.env
```

If the previous manifest is the same release, use `.release.env` as the
candidate instead. Verify public health, local ingestion, container images,
profile `prod`, PostgreSQL, and all three volumes. Preserve the AWS certificate
files for investigation; deleting or rotating them is a separate authorized
credential operation.

Terraform destroy is also separate from runtime rollback. Run it only from the
reviewed remote state and temporary human identity, then independently confirm
that application resources are gone. The protected state bucket should be
retained unless a later explicit decision authorizes its state migration and
destruction.
