#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -d "$ROOT/Firmware" ]]; then
  SRC="$ROOT/Firmware"
else
  SRC="$ROOT/firmware"
fi
BUILD="$SRC/build"
SIM="$BUILD/dpls_simulator"
cmake -S "$SRC" -B "$BUILD" >/dev/null
cmake --build "$BUILD" --target dpls_simulator
NATIVE="$ROOT/tools/dpls-lab/native"
if [[ ! -x "$NATIVE/dpls-ble" || "$NATIVE/DplsBle.swift" -nt "$NATIVE/dpls-ble" ]]; then
  swiftc -O -framework CoreBluetooth -framework Foundation \
    -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker "$NATIVE/Info.plist" \
    -o "$NATIVE/dpls-ble" "$NATIVE/DplsBle.swift"
fi
"$ROOT/mobile/gradlew" -p "$ROOT/mobile" :web:wasmJsBrowserDistribution
export DPLS_SIMULATOR="$SIM"
exec bun run "$ROOT/tools/dpls-lab/server.ts"
