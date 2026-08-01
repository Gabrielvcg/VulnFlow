#!/usr/bin/env bash
set -euo pipefail

if (( $# != 1 )); then
  echo "Usage: deploy.sh <candidate-release-env-file>" >&2
  exit 64
fi

candidate_release=$1
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
deploy_root=$(cd "${script_dir}/.." && pwd)
runtime_env="${deploy_root}/runtime/.env.prod"
current_release="${deploy_root}/.release.env"
previous_release="${deploy_root}/.release.previous.env"
lock_file="${deploy_root}/.deploy.lock"
health_check="${script_dir}/health-check.sh"
compose_file="${script_dir}/docker-compose.prod.yml"
aws_compose_file="${script_dir}/docker-compose.aws.yml"

for command in docker flock realpath; do
  command -v "${command}" >/dev/null 2>&1 || { echo "Required command is unavailable: ${command}" >&2; exit 69; }
done
docker compose version >/dev/null 2>&1 || { echo "Docker Compose v2 is required." >&2; exit 69; }

if [[ ! -f "${runtime_env}" ]]; then
  echo "Runtime environment file is missing: ${runtime_env}" >&2
  exit 65
fi
if [[ ! -f "${candidate_release}" ]]; then
  echo "Candidate release file is missing." >&2
  exit 65
fi

candidate_real=$(realpath "${candidate_release}")
if [[ "${candidate_real}" != "${deploy_root}/.release.next.env" ]]; then
  echo "Candidate release must be ${deploy_root}/.release.next.env" >&2
  exit 65
fi

validate_release() {
  local release=$1
  local backend_sha
  local agent_sha
  local release_sha
  [[ $(wc -l < "${release}") -eq 3 ]] || return 1
  grep -Eq '^VULNFLOW_BACKEND_IMAGE=ghcr\.io/[a-z0-9][a-z0-9._/-]*/vulnflow-backend:[0-9a-f]{40}$' "${release}" || return 1
  grep -Eq '^VULNFLOW_AGENT_IMAGE=ghcr\.io/[a-z0-9][a-z0-9._/-]*/vulnflow-agent:[0-9a-f]{40}$' "${release}" || return 1
  grep -Eq '^VULNFLOW_RELEASE_SHA=[0-9a-f]{40}$' "${release}" || return 1
  backend_sha=$(sed -n 's/^VULNFLOW_BACKEND_IMAGE=.*://p' "${release}")
  agent_sha=$(sed -n 's/^VULNFLOW_AGENT_IMAGE=.*://p' "${release}")
  release_sha=$(sed -n 's/^VULNFLOW_RELEASE_SHA=//p' "${release}")
  [[ "${backend_sha}" == "${release_sha}" && "${agent_sha}" == "${release_sha}" ]] || return 1
}

if ! validate_release "${candidate_real}"; then
  echo "Candidate release manifest is invalid; only immutable GHCR SHA references are accepted." >&2
  exit 65
fi

chmod 600 "${runtime_env}" "${candidate_real}"
exec 9>"${lock_file}"
if ! flock -n 9; then
  echo "Another VulnFlow deployment is already running on this VPS." >&2
  exit 75
fi

compose() {
  local release=$1
  shift
  local compose_args=(--env-file "${runtime_env}" --env-file "${release}" -f "${compose_file}")
  if grep -Eq '^VULNFLOW_AWS_MODE=true$' "${runtime_env}"; then
    [[ -f "${aws_compose_file}" ]] || {
      echo "AWS mode is enabled but the Compose override is missing: ${aws_compose_file}" >&2
      return 1
    }
    compose_args+=(-f "${aws_compose_file}")
  fi
  docker compose "${compose_args[@]}" "$@"
}

diagnostics() {
  local release=$1
  echo "Deployment diagnostics (bounded to the latest 100 log lines per service):" >&2
  compose "${release}" ps >&2 || true
  compose "${release}" logs --no-color --tail 100 postgres backend agent >&2 || true
}

deploy_release() {
  local release=$1
  compose "${release}" config --quiet >/dev/null || return 1
  compose "${release}" pull backend agent || return 1
  compose "${release}" up -d --remove-orphans || return 1
  "${health_check}" "${runtime_env}" "${release}" || return 1
}

had_current=false
if [[ -f "${current_release}" ]]; then
  if ! validate_release "${current_release}"; then
    echo "Current release manifest is invalid; refusing to replace rollback state." >&2
    exit 65
  fi
  had_current=true
fi

atomic_install() {
  local source=$1
  local destination=$2
  install -m 600 "${source}" "${destination}.tmp"
  mv -f "${destination}.tmp" "${destination}"
}

release_sha=$(sed -n 's/^VULNFLOW_RELEASE_SHA=//p' "${candidate_real}")

echo "Deploying VulnFlow release ${release_sha}."
if deploy_release "${candidate_real}"; then
  if [[ "${had_current}" == "true" ]]; then
    atomic_install "${current_release}" "${previous_release}"
  fi
  atomic_install "${candidate_real}" "${current_release}"
  echo "VulnFlow release ${release_sha} deployed successfully."
  exit 0
fi

diagnostics "${candidate_real}"
failed_release="${deploy_root}/.release.failed-${release_sha}.env"
atomic_install "${candidate_real}" "${failed_release}"

if [[ "${had_current}" != "true" ]]; then
  echo "Deployment failed and no prior release exists for automatic rollback." >&2
  exit 1
fi

echo "Deployment failed; restoring the previous immutable release." >&2
if deploy_release "${current_release}"; then
  rollback_sha=$(sed -n 's/^VULNFLOW_RELEASE_SHA=//p' "${current_release}")
  echo "Rollback to release ${rollback_sha} succeeded; the deployment remains failed for investigation." >&2
  exit 1
fi

diagnostics "${current_release}"
echo "Deployment and automatic rollback both failed. Manual recovery is required." >&2
exit 2
