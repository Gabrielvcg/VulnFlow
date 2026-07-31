#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
trivy_path=${VULNFLOW_TRIVY_PATH:-trivy}
demo_image=${VULNFLOW_DEMO_IMAGE:-alpine:3.15}

if ! command -v "${trivy_path}" >/dev/null 2>&1; then
  echo "Trivy is not installed or VULNFLOW_TRIVY_PATH is invalid." >&2
  exit 1
fi
"${trivy_path}" --version

# shellcheck source=scripts/agent-demo-lib.sh
source "${script_dir}/agent-demo-lib.sh"
run_agent_demo "$(command -v "${trivy_path}")" "${demo_image}" "real-trivy"
