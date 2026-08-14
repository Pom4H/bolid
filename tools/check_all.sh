#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

bash tools/check_repo_layout.sh
python3 tools/test_dpls_protocol_crc.py
bash tools/coverage_firmware.sh
bash tools/lint_firmware.sh
bash tools/check_mobile.sh
