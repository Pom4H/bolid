#!/usr/bin/env bash
# Soft-BLE E2E: DplsClient ↔ host dpls_simulator (no phone, no PHY6252 board).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/firmware/build"
SIM="$BUILD/dpls_simulator"

cmake -S "$ROOT/firmware" -B "$BUILD" >/dev/null
cmake --build "$BUILD" --target dpls_simulator

test -x "$SIM"

(
    cd "$ROOT/mobile"
    DPLS_SIMULATOR="$SIM" \
        ./gradlew :interop:jvmTest --rerun-tasks \
        --tests ru.bolid.testdpls.interop.SoftBleBridgeTest
)

echo "soft-BLE E2E passed"
