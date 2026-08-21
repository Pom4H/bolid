#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

bash tools/run_host_invariant_gate.sh
bash tools/coverage_firmware.sh
bash tools/lint_firmware.sh
bash tools/check_mobile.sh
