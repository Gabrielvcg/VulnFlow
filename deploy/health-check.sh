#!/usr/bin/env bash
set -euo pipefail

if (( $# != 2 )); then
  echo "Usage: health-check.sh <runtime-env-file> <release-env-file>" >&2
  exit 64
fi

runtime_env=$1
release_env=$2
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
compose_file="${script_dir}/docker-compose.prod.yml"
compose=(docker compose --env-file "${runtime_env}" --env-file "${release_env}" -f "${compose_file}")

for required_file in "${runtime_env}" "${release_env}" "${compose_file}"; do
  if [[ ! -f "${required_file}" ]]; then
    echo "Required deployment file is missing: ${required_file}" >&2
    exit 65
  fi
done

container_id() {
  "${compose[@]}" ps --all -q "$1" | head -n 1
}

container_state() {
  local container=$1
  if [[ -z "${container}" ]]; then
    printf 'missing'
    return
  fi
  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container}" 2>/dev/null || printf 'unknown'
}

backend_endpoint_is_up() {
  local container=$1
  docker exec "${container}" sh -c \
    'wget -q -O - http://127.0.0.1:8080/actuator/health | grep -q '\''"status":"UP"'\'''
}

attempts=30
delay_seconds=5

for (( attempt = 1; attempt <= attempts; attempt++ )); do
  postgres_id=$(container_id postgres)
  backend_id=$(container_id backend)
  agent_id=$(container_id agent)

  postgres_state=$(container_state "${postgres_id}")
  backend_state=$(container_state "${backend_id}")
  agent_state=$(container_state "${agent_id}")

  if [[ "${postgres_state}" == "healthy" && "${backend_state}" == "healthy" && "${agent_state}" == "running" ]] \
      && backend_endpoint_is_up "${backend_id}"; then
    sleep 10
    agent_state=$(container_state "$(container_id agent)")
    backend_state=$(container_state "$(container_id backend)")
    if [[ "${agent_state}" == "running" && "${backend_state}" == "healthy" ]]; then
      echo "Deployment health check passed: PostgreSQL and backend are healthy; agent is running."
      exit 0
    fi
  fi

  echo "Health attempt ${attempt}/${attempts}: postgres=${postgres_state} backend=${backend_state} agent=${agent_state}"
  sleep "${delay_seconds}"
done

echo "Deployment health check failed after $(( attempts * delay_seconds )) seconds." >&2
exit 1
