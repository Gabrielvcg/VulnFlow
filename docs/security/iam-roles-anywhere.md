# IAM Roles Anywhere for the VulnFlow VPS

VulnFlow uses IAM Roles Anywhere only for the backend workload running outside
AWS. Human Terraform operators use the separate `VulnFlowTerraformOperator`
AssumeRole flow instead. Neither path uses long-lived AWS access keys.

Creating or updating a Roles Anywhere profile has an AWS-documented
`iam:PassRole` dependency. The human Terraform operator can pass exactly
`arn:aws:iam::160172542031:role/vulnflow-demo-backend-role` to
`rolesanywhere.amazonaws.com`; it cannot pass any other workload role through
that statement.

## Prepared trust boundary

The optional Terraform module creates four resources only when
`enable_vps_roles_anywhere=true`:

1. An IAM Roles Anywhere trust anchor for a reviewed public CA certificate.
2. An IAM role trusted only by `rolesanywhere.amazonaws.com` when the source
   account, exact trust anchor ARN, and `x509Subject/CN` all match.
3. An inline least-privilege role policy.
4. A profile with 15-minute sessions and an identical session policy.

The effective backend permissions are the intersection of the role and session
policies: S3 report object put/get/delete, SQS ingestion send, and DynamoDB
result get/query. The profile accepts only the single backend role and does not
accept caller-selected session names.

AWS recommends binding Roles Anywhere role trust to the trust anchor with
`aws:SourceArn` and constraining certificate attributes. The default profile
attribute mappings expose the subject CN used by the Terraform trust policy.
See the official [trust model](https://docs.aws.amazon.com/rolesanywhere/latest/userguide/trust-model.html)
and [attribute mapping guidance](https://docs.aws.amazon.com/rolesanywhere/latest/userguide/attribute-mapping-and-trust-policy.html).

## Certificate ceremony

Reuse a maintained organizational CA if one exists. Otherwise use an encrypted
offline CA; do not create AWS Private CA for this demo because it introduces a
material recurring charge. The CA private key must never be stored on the VPS,
in this repository, in Terraform state, or in GitHub.

The eventual ceremony is deliberately manual and is not executed by CI:

1. Create the encrypted CA key and CA certificate on an offline, backed-up
   workstation. The CA certificate must be X.509v3 with `CA:TRUE`, SHA-256 or
   stronger, and `keyCertSign` usage.
2. Generate a 3072-bit or stronger private key on the VPS. Keep it unencrypted
   only because the backend must start unattended, and protect it through file
   ownership, mode `0400`, host backup exclusions, and restricted root access.
3. Generate a CSR with subject CN `vulnflow-backend.vacaro.es` and URI SAN
   `spiffe://vulnflow/prod/backend`.
4. Sign the CSR offline for a short validity period. The leaf certificate must
   be X.509v3 with `CA:FALSE`, `digitalSignature`, SHA-256 or stronger, and
   client-auth extended usage.
5. Transfer only the leaf certificate back to the VPS. Never transfer the CA
   private key.
6. Supply only the public CA certificate to the uncommitted Terraform tfvars
   input and review the new four-resource identity plan before apply.

IAM Roles Anywhere supports CRLs. Before production-scale use, add an owned CRL
publication/renewal procedure. For the initial controlled demo, emergency
revocation is to disable the profile and trust anchor, then replace the leaf
key/certificate through an authorized operation. Short certificate validity is
not a substitute for revocation ownership.

## Runtime credential process

The backend image contains AWS signing helper 1.8.4, downloaded from the
official AWS endpoint and verified against the published SHA-256 during image
build. Alpine compatibility is supplied by `gcompat`; CI executes the helper
from the built image and checks its version.

After the identity apply and certificate ceremony, run the repository's
`deploy/prepare-aws-runtime.sh` as root with the three Terraform output ARNs and
the VPS credential directory. It validates ARN scope, certificate expiry,
subject CN, matching public/private keys, and then creates a mode-`0400` AWS
profile using `credential_process`. The files are owned by the existing backend
UID/GID and mounted read-only. The helper returns credentials directly to the
AWS SDK and refreshes them as required; it does not write an AWS credentials
file or expose a local credential HTTP service. See AWS's official
[credential helper documentation](https://docs.aws.amazon.com/rolesanywhere/latest/userguide/credential-helper.html).

Do not add `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, or
`AWS_SESSION_TOKEN` to Compose, the VPS environment, GitHub, or the repository.
