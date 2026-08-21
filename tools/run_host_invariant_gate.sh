#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${DPLS_HOST_BUILD_DIR:-$ROOT/firmware/build-invariants}"

export ASAN_OPTIONS="${ASAN_OPTIONS:-detect_leaks=1:halt_on_error=1:abort_on_error=1}"
export UBSAN_OPTIONS="${UBSAN_OPTIONS:-halt_on_error=1:print_stacktrace=1}"

cd "$ROOT"

echo '== repository / CI / architecture invariants =='
bash tools/check_repo_layout.sh
python3 tools/test_ci_contract.py
python3 tools/test_phy6252_linker_parity.py
python3 tools/test_factory_identity.py
python3 tools/test_flash_firmware.py
python3 tools/test_phy6252_snv_guard_contract.py
python3 tools/test_ble_timeout_contract.py
python3 tools/test_android_ble_connection_contract.py
python3 tools/test_dpls_protocol_crc.py
python3 tools/session_capture/test_session_capture.py
python3 tools/architecture_guard.py

echo '== host production-core tests with ASan + UBSan =='
rm -rf "$BUILD_DIR"
cmake \
  -S firmware \
  -B "$BUILD_DIR" \
  -DCMAKE_BUILD_TYPE=Debug \
  -DENABLE_SANITIZERS=ON
cmake --build "$BUILD_DIR" --parallel "${DPLS_BUILD_JOBS:-2}"
ctest --test-dir "$BUILD_DIR" --output-on-failure --timeout 45

echo '== differential wire replay =='
DPLS_SIMULATOR="$BUILD_DIR/dpls_simulator" \
  python3 tools/session_capture/test_differential_replay.py

echo 'Host invariant gate: PASS'
