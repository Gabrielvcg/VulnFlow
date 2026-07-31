#!/usr/bin/env sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
api_url=${API_URL:-http://localhost:8080}
api_key=${VULNFLOW_API_KEY:?VULNFLOW_API_KEY must be set}
report_file="${project_dir}/sample-data/trivy-multiple.json"
invalid_report=$(mktemp)
trap 'rm -f "${invalid_report}"' EXIT
printf '%s' '{"Metadata":{"OS":{"Family":"demo"}}}' > "${invalid_report}"

json_field() {
  printf '%s' "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p"
}

curl_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    printf '%s' "$1"
  fi
}

wait_for_job() {
  job_id=$1
  expected_status=$2
  timeout_seconds=${3:-60}
  started_at=$(date +%s)
  while :; do
    job_response=$(curl --fail-with-body --silent --show-error \
      -H "X-API-Key: ${api_key}" \
      "${api_url}/api/v1/ingestion-jobs/${job_id}")
    job_status=$(json_field "${job_response}" status)
    printf 'Job %s status: %s\n' "${job_id}" "${job_status}"
    if [ "${job_status}" = "${expected_status}" ]; then
      printf '%s\n' "${job_response}"
      return 0
    fi
    if [ "${job_status}" = "DEAD_LETTER" ] || [ "${job_status}" = "COMPLETED" ]; then
      echo "Job reached unexpected terminal status ${job_status}." >&2
      return 1
    fi
    now=$(date +%s)
    if [ $((now - started_at)) -ge "${timeout_seconds}" ]; then
      echo "Timed out waiting for job ${job_id}." >&2
      return 1
    fi
    sleep 1
  done
}

echo "Checking VulnFlow health..."
curl --fail-with-body --silent --show-error "${api_url}/actuator/health"
printf '\n'

echo "Creating demo asset..."
asset_response=$(curl --fail-with-body --silent --show-error \
  -X POST "${api_url}/api/v1/assets" \
  -H "X-API-Key: ${api_key}" \
  -H "Content-Type: application/json" \
  -d '{"name":"VulnFlow async demo","type":"CONTAINER_IMAGE","externalReference":"portfolio-service:2.2.0"}')
printf '%s\n' "${asset_response}"
asset_id=$(json_field "${asset_response}" id)
if [ -z "${asset_id}" ]; then
  echo "Could not read the asset id from the API response." >&2
  exit 1
fi

echo "Submitting a valid Trivy report..."
accepted_response=$(curl --fail-with-body --silent --show-error \
  -X POST "${api_url}/api/v1/scans/trivy" \
  -H "X-API-Key: ${api_key}" \
  -F "assetId=${asset_id}" \
  -F "file=@$(curl_path "${report_file}");type=application/json")
printf '%s\n' "${accepted_response}"
job_id=$(json_field "${accepted_response}" jobId)
outcome=$(json_field "${accepted_response}" outcome)
if [ -z "${job_id}" ] || [ "${outcome}" != "ACCEPTED" ]; then
  echo "The valid report was not accepted as a new asynchronous job." >&2
  exit 1
fi

wait_for_job "${job_id}" COMPLETED 60

echo "Querying imported findings..."
curl --fail-with-body --silent --show-error \
  -H "X-API-Key: ${api_key}" \
  "${api_url}/api/v1/findings?assetId=${asset_id}&size=20"
printf '\n'

echo "Submitting the same report again to demonstrate deduplication..."
duplicate_response=$(curl --fail-with-body --silent --show-error \
  -X POST "${api_url}/api/v1/scans/trivy" \
  -H "X-API-Key: ${api_key}" \
  -F "assetId=${asset_id}" \
  -F "file=@$(curl_path "${report_file}");type=application/json")
printf '%s\n' "${duplicate_response}"
if [ "$(json_field "${duplicate_response}" outcome)" != "DUPLICATE" ]; then
  echo "The completed report was not deduplicated." >&2
  exit 1
fi

echo "Submitting a semantically invalid report..."
invalid_response=$(curl --fail-with-body --silent --show-error \
  -X POST "${api_url}/api/v1/scans/trivy" \
  -H "X-API-Key: ${api_key}" \
  -F "assetId=${asset_id}" \
  -F "file=@$(curl_path "${invalid_report}");type=application/json")
printf '%s\n' "${invalid_response}"
invalid_job_id=$(json_field "${invalid_response}" jobId)
if [ -z "${invalid_job_id}" ]; then
  echo "The invalid report did not create a persistent job." >&2
  exit 1
fi

wait_for_job "${invalid_job_id}" DEAD_LETTER 60
echo "VulnFlow asynchronous demo completed."
