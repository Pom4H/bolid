#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${DPLS_HOST_BUILD_DIR:-$ROOT/firmware/build-invariants}"
TARGET="$ROOT/firmware/targets/phy6252/source/dplsBLEPeripheral.c"

# Эта ветка проверяет только физический путь PHY6252 reset -> GAP -> radio.
# Product-level host contracts здесь намеренно неприменимы: DPLS runtime из
# target wrapper полностью исключён. Пропускаем их, чтобы CI дошёл до реальных
# AC6/GCC/Firmverse target jobs.
if grep -q 'BOLID-BOOT-PROBE' "$TARGET"; then
    echo 'Host gate: SKIP for minimal PHY6252 radio boot probe'
    exit 0
fi

export ASAN_OPTIONS="${ASAN_OPTIONS:-detect_leaks=1:halt_on_error=1:abort_on_error=1}"
export UBSAN_OPTIONS="${UBSAN_OPTIONS:-halt_on_error=1:print_stacktrace=1}"

cd "$ROOT"

bash tools/check_repo_layout.sh
python3 tools/test_ci_contract.py
python3 tools/test_flash_firmware.py
python3 tools/test_ble_timeout_contract.py
python3 tools/test_dpls_protocol_crc.py
python3 tools/session_capture/test_session_capture.py
python3 tools/architecture_guard.py

rm -rf "$BUILD_DIR"
cmake \
  -S firmware \
  -B "$BUILD_DIR" \
  -DCMAKE_BUILD_TYPE=Debug \
  -DENABLE_SANITIZERS=ON
cmake --build "$BUILD_DIR" --parallel "${DPLS_BUILD_JOBS:-2}"
ctest --test-dir "$BUILD_DIR" --output-on-failure --timeout 45

DPLS_SIMULATOR="$BUILD_DIR/dpls_simulator" \
  python3 tools/session_capture/test_differential_replay.py

echo 'Host gate: PASS'
