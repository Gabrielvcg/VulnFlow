#!/usr/bin/env sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
api_url=${API_URL:-http://localhost:8080}
report_file="${project_dir}/sample-data/trivy-multiple.json"
curl_report_file=${report_file}
if command -v cygpath >/dev/null 2>&1; then
  curl_report_file=$(cygpath -w "${report_file}")
fi

echo "Checking VulnFlow health..."
curl --fail-with-body --silent --show-error "${api_url}/actuator/health"
printf '\n'

echo "Creating demo asset..."
asset_response=$(curl --fail-with-body --silent --show-error \
  -X POST "${api_url}/api/v1/assets" \
  -H "Content-Type: application/json" \
  -d '{"name":"VulnFlow demo image","type":"CONTAINER_IMAGE","externalReference":"portfolio-service:2.1.0"}')
printf '%s\n' "${asset_response}"

asset_id=$(printf '%s' "${asset_response}" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
if [ -z "${asset_id}" ]; then
  echo "Could not read the asset id from the API response." >&2
  exit 1
fi

echo "Importing Trivy report for asset ${asset_id}..."
curl --fail-with-body --silent --show-error \
  -X POST "${api_url}/api/v1/scans/trivy" \
  -F "assetId=${asset_id}" \
  -F "file=@${curl_report_file};type=application/json"
printf '\n'

echo "Querying findings..."
curl --fail-with-body --silent --show-error \
  "${api_url}/api/v1/findings?assetId=${asset_id}&size=20"
printf '\n'

echo "Querying dashboard summary..."
curl --fail-with-body --silent --show-error \
  "${api_url}/api/v1/dashboard/summary"
printf '\n'

echo "VulnFlow demo completed."
