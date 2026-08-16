#!/usr/bin/env bash
# Run every device-free zmu scenario: firmware unit tests + Kotlin↔ARM E2E matrix.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ZMU_BIN="${1:-${ZMU_BIN:-}}"

if [[ -z "$ZMU_BIN" || ! -x "$ZMU_BIN" ]]; then
    echo "usage: $0 /path/to/zmu-cortex-m0" >&2
    echo "hint: bash tools/fetch_zmu.sh && $0 tmp/zmu/target/release/zmu-cortex-m0" >&2
    exit 2
fi

bash "$ROOT/tools/zmu_firmware_tests.sh" "$ZMU_BIN"
bash "$ROOT/tools/zmu_e2e.sh" "$ZMU_BIN"
echo "zmu all scenarios passed"
