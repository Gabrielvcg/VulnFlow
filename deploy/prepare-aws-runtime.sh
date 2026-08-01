#!/usr/bin/env bash
set -euo pipefail

if (( EUID != 0 )); then
  echo "Run this one-time preparation as root after the AWS identity apply is authorized." >&2
  exit 77
fi

if (( $# != 4 )); then
  echo "Usage: prepare-aws-runtime.sh <trust-anchor-arn> <profile-arn> <role-arn> <credentials-directory>" >&2
  exit 64
fi

for required_command in openssl sha256sum grep cut install; do
  command -v "${required_command}" >/dev/null 2>&1 || {
    echo "Required command is unavailable: ${required_command}" >&2
    exit 69
  }
done

trust_anchor_arn=$1
profile_arn=$2
role_arn=$3
credentials_dir=$4
account_id=160172542031
region=eu-west-1
certificate_cn=vulnflow-backend.vacaro.es

[[ "${trust_anchor_arn}" =~ ^arn:aws:rolesanywhere:${region}:${account_id}:trust-anchor/[0-9a-f-]{36}$ ]] || {
  echo "The trust anchor ARN is invalid." >&2
  exit 65
}
[[ "${profile_arn}" =~ ^arn:aws:rolesanywhere:${region}:${account_id}:profile/[0-9a-f-]{36}$ ]] || {
  echo "The Roles Anywhere profile ARN is invalid." >&2
  exit 65
}
[[ "${role_arn}" == "arn:aws:iam::${account_id}:role/vulnflow-demo-backend-role" ]] || {
  echo "The backend role ARN is invalid." >&2
  exit 65
}
[[ "${credentials_dir}" =~ ^/[A-Za-z0-9._/-]+$ && "${credentials_dir}" != "/" && "${credentials_dir}" != *".."* ]] || {
  echo "The credentials directory is unsafe." >&2
  exit 65
}

certificate_file="${credentials_dir}/client.pem"
private_key_file="${credentials_dir}/client-key.pem"
for required_file in "${certificate_file}" "${private_key_file}"; do
  [[ -f "${required_file}" ]] || {
    echo "Required workload credential is missing: ${required_file}" >&2
    exit 65
  }
done

openssl x509 -in "${certificate_file}" -noout -checkend 86400 >/dev/null || {
  echo "The workload certificate is invalid or expires within 24 hours." >&2
  exit 65
}
openssl x509 -in "${certificate_file}" -noout -subject -nameopt RFC2253 \
  | grep -Fq "CN=${certificate_cn}" || {
    echo "The workload certificate subject CN is not ${certificate_cn}." >&2
    exit 65
  }

certificate_public_key=$(openssl x509 -in "${certificate_file}" -pubkey -noout \
  | openssl pkey -pubin -outform DER 2>/dev/null | sha256sum | cut -d' ' -f1)
private_public_key=$(openssl pkey -in "${private_key_file}" -pubout -outform DER 2>/dev/null \
  | sha256sum | cut -d' ' -f1)
[[ -n "${certificate_public_key}" && "${certificate_public_key}" == "${private_public_key}" ]] || {
  echo "The workload certificate and private key do not match." >&2
  exit 65
}

umask 077
config_file="${credentials_dir}/config"
config_tmp="${config_file}.tmp"
printf '%s\n' \
  '[profile vulnflow-roles-anywhere]' \
  "region=${region}" \
  "credential_process=/usr/local/bin/aws_signing_helper credential-process --certificate /run/vulnflow-aws/client.pem --private-key /run/vulnflow-aws/client-key.pem --trust-anchor-arn ${trust_anchor_arn} --profile-arn ${profile_arn} --role-arn ${role_arn} --session-duration 900" \
  > "${config_tmp}"
install -o 100 -g 101 -m 0400 "${config_tmp}" "${config_file}"
rm -f "${config_tmp}"
chown 100:101 "${certificate_file}" "${private_key_file}"
chmod 0444 "${certificate_file}"
chmod 0400 "${private_key_file}"
chown 100:101 "${credentials_dir}"
chmod 0500 "${credentials_dir}"

echo "VulnFlow Roles Anywhere runtime files prepared without storing AWS access keys."
