#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "${script_dir}/.." && pwd)
# shellcheck source=scripts/agent-demo-lib.sh
source "${script_dir}/agent-demo-lib.sh"
demo_tools=$(mktemp -d "${repo_root}/.agent-demo-tools.XXXXXX")
trap 'rm -rf -- "${demo_tools}"' EXIT

if agent_demo_windows_host; then
    fake_trivy="${demo_tools}/fake-trivy.cmd"
    cat > "${fake_trivy}" <<'CMD'
@echo off
if "%~1"=="--version" (
  echo fake-trivy 0.3.0
  exit /b 0
)
:loop
if "%~1"=="" exit /b 2
if "%~1"=="--output" (
  copy /Y "%FAKE_TRIVY_REPORT%" "%~2" >nul
  exit /b 0
)
shift
goto loop
CMD
    export FAKE_TRIVY_REPORT
    FAKE_TRIVY_REPORT=$(agent_demo_java_path "${repo_root}/backend/src/test/resources/trivy-report.json")
else
    fake_trivy="${demo_tools}/fake-trivy"
    cat > "${fake_trivy}" <<'SH'
#!/usr/bin/env sh
set -eu
if [ "${1:-}" = "--version" ]; then
  printf '%s\n' 'fake-trivy 0.3.0'
  exit 0
fi
while [ "$#" -gt 0 ]; do
  if [ "$1" = "--output" ]; then
    shift
    cp -- "${FAKE_TRIVY_REPORT}" "$1"
    exit 0
  fi
  shift
done
exit 2
SH
    chmod 700 "${fake_trivy}"
    export FAKE_TRIVY_REPORT="${repo_root}/backend/src/test/resources/trivy-report.json"
fi

run_agent_demo "${fake_trivy}" "integration-test:$(date +%s)-$$" "fake-trivy"
