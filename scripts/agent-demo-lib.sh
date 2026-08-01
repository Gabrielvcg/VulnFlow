#!/usr/bin/env bash
set -euo pipefail

agent_demo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

agent_demo_wsl_host() {
  [ "$(uname -s)" = "Linux" ] && grep -qi microsoft /proc/sys/kernel/osrelease 2>/dev/null
}

agent_demo_windows_host() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) return 0 ;;
    Linux*) agent_demo_wsl_host ;;
    *) return 1 ;;
  esac
}

agent_demo_java_path() {
  local path=$1
  if agent_demo_windows_host; then
    if command -v cygpath >/dev/null 2>&1; then
      cygpath -w "${path}"
    else
      wslpath -w "${path}"
    fi
  else
    printf '%s\n' "${path}"
  fi
}

agent_demo_build() {
  (
    cd "${agent_demo_root}/agent"
    if agent_demo_windows_host; then
      cmd.exe /d /c mvnw.cmd --batch-mode --no-transfer-progress -DskipTests package
    else
      ./mvnw --batch-mode --no-transfer-progress -DskipTests package
    fi
  )
}

agent_demo_java() {
  if agent_demo_wsl_host; then
    local bridge_variables
    bridge_variables="VULNFLOW_API_URL:VULNFLOW_API_KEY:VULNFLOW_AGENT_ID:VULNFLOW_SCAN_INTERVAL"
    bridge_variables+=":VULNFLOW_TRIVY_PATH:VULNFLOW_AGENT_DATA_DIR:VULNFLOW_AGENT_TEMP_DIR"
    bridge_variables+=":VULNFLOW_AGENT_MAX_CONCURRENT_SCANS:VULNFLOW_AGENT_UPLOAD_RETRY_INTERVAL"
    bridge_variables+=":VULNFLOW_TARGETS_FILE:FAKE_TRIVY_REPORT"
    WSLENV="${WSLENV:+${WSLENV}:}${bridge_variables}" java.exe "$@"
  elif agent_demo_windows_host; then
    java.exe "$@"
  else
    java "$@"
  fi
}

agent_demo_python() {
  if command -v python3 >/dev/null 2>&1; then
    python3 "$@"
  else
    python "$@"
  fi
}

agent_demo_json_field() {
  local json=$1
  local field=$2
  agent_demo_python - "${json}" "${field}" <<'PY'
import json
import sys
value = json.loads(sys.argv[1]).get(sys.argv[2])
print("" if value is None else value)
PY
}

agent_demo_latest_metadata() {
  local data_dir=$1
  agent_demo_python - "${data_dir}" <<'PY'
import glob
import json
import os
import sys
files = glob.glob(os.path.join(sys.argv[1], "outbox", "items", "*", "metadata.json"))
if not files:
    raise SystemExit("No outbox metadata was created")
items = []
for path in files:
    with open(path, encoding="utf-8") as handle:
        items.append(json.load(handle))
print(json.dumps(max(items, key=lambda item: item["createdAt"]), separators=(",", ":")))
PY
}

agent_demo_wait_for_health() {
  local api_url=$1
  for _ in $(seq 1 60); do
    if curl --silent --fail "${api_url}/actuator/health" >/dev/null; then
      return
    fi
    sleep 1
  done
  echo "VulnFlow did not become healthy." >&2
  exit 1
}

agent_demo_wait_for_job() {
  local api_url=$1
  local api_key=$2
  local job_id=$3
  for _ in $(seq 1 60); do
    local response
    response=$(curl --silent --fail \
      -H "X-API-Key: ${api_key}" \
      "${api_url}/api/v1/ingestion-jobs/${job_id}")
    local status
    status=$(agent_demo_json_field "${response}" status)
    printf 'Job %s status: %s\n' "${job_id}" "${status}"
    if [ "${status}" = "COMPLETED" ]; then
      printf '%s\n' "${response}"
      return
    fi
    if [ "${status}" = "DEAD_LETTER" ]; then
      printf '%s\n' "${response}" >&2
      exit 1
    fi
    sleep 1
  done
  echo "Timed out waiting for the ingestion job." >&2
  exit 1
}

run_agent_demo() (
  local trivy_path=$1
  local image=$2
  local label=$3
  local api_url=${VULNFLOW_API_URL:-http://127.0.0.1:8080}
  local api_key=${VULNFLOW_API_KEY:-local-development-only-api-key}
  local demo_dir
  demo_dir=$(mktemp -d "${agent_demo_root}/.agent-demo.XXXXXX")
  trap 'rm -rf -- "${demo_dir}"' EXIT

  echo "Starting VulnFlow for the ${label} agent demo..."
  docker compose -f "${agent_demo_root}/docker-compose.yml" up --build -d
  agent_demo_wait_for_health "${api_url}"

  echo "Building VulnFlow Agent..."
  agent_demo_build

  local targets_file="${demo_dir}/targets.yml"
  local data_dir="${demo_dir}/data"
  local temp_dir="${demo_dir}/temp"
  mkdir -p "${data_dir}" "${temp_dir}"
  printf 'targets:\n  - name: agent-demo-image\n    type: CONTAINER_IMAGE\n    reference: %s\n' "${image}" > "${targets_file}"

  local java_targets java_data java_temp java_trivy
  java_targets=$(agent_demo_java_path "${targets_file}")
  java_data=$(agent_demo_java_path "${data_dir}")
  java_temp=$(agent_demo_java_path "${temp_dir}")
  java_trivy=$(agent_demo_java_path "${trivy_path}")

  agent_demo_run_once() {
    VULNFLOW_API_URL="${api_url}" \
    VULNFLOW_API_KEY="${api_key}" \
    VULNFLOW_AGENT_ID="${label}-demo-agent" \
    VULNFLOW_SCAN_INTERVAL=1h \
    VULNFLOW_TRIVY_PATH="${java_trivy}" \
    VULNFLOW_AGENT_DATA_DIR="${java_data}" \
    VULNFLOW_AGENT_TEMP_DIR="${java_temp}" \
    VULNFLOW_AGENT_MAX_CONCURRENT_SCANS=1 \
    VULNFLOW_AGENT_UPLOAD_RETRY_INTERVAL=1s \
    VULNFLOW_TARGETS_FILE="${java_targets}" \
      agent_demo_java -jar "$(agent_demo_java_path "${agent_demo_root}/agent/target/vulnflow-agent-0.4.2.jar")" --once
  }

  echo "Running the first agent cycle..."
  agent_demo_run_once
  echo "Retained outbox reports:"
  find "${data_dir}/outbox/items" -type f -name report.json -print
  local first_metadata first_job_id first_asset_id
  first_metadata=$(agent_demo_latest_metadata "${data_dir}")
  printf '%s\n' "${first_metadata}"
  first_job_id=$(agent_demo_json_field "${first_metadata}" backendJobId)
  first_asset_id=$(agent_demo_json_field "${first_metadata}" assetId)
  if [ -z "${first_job_id}" ]; then
    echo "The first upload did not return a backend job." >&2
    exit 1
  fi
  agent_demo_wait_for_job "${api_url}" "${api_key}" "${first_job_id}"

  echo "Findings created for the resolved asset:"
  curl --silent --fail \
    -H "X-API-Key: ${api_key}" \
    "${api_url}/api/v1/findings?assetId=${first_asset_id}"
  printf '\n'

  echo "Running a second cycle to demonstrate backend deduplication..."
  agent_demo_run_once
  local second_metadata second_outcome
  second_metadata=$(agent_demo_latest_metadata "${data_dir}")
  second_outcome=$(agent_demo_json_field "${second_metadata}" backendOutcome)
  printf '%s\n' "${second_metadata}"
  if [ "${second_outcome}" != "DUPLICATE" ]; then
    echo "The second upload was not deduplicated; outcome=${second_outcome}" >&2
    exit 1
  fi
  echo "VulnFlow ${label} agent demo completed."
)
