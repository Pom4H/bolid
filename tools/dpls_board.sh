#!/usr/bin/env bash
# PHY6252 / AI-Thinker PB-03F-Kit inspector: hex GPIO pads, ADC mux, BLE host.
# --once кадр, --air peripheral, --listen сокет без TTY. Product lab: tools/dpls_lab.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -d "$ROOT/firmware" ]]; then
  SRC="$ROOT/firmware"
else
  SRC="$ROOT/Firmware"
fi
BUILD="$SRC/build"
SIM="$BUILD/dpls_simulator"
cmake -S "$SRC" -B "$BUILD" >/dev/null
cmake --build "$BUILD" --target dpls_simulator
# Guest chip emulator: third_party/phy6252-emu (or export DPLS_ZMU=/path/to/phy6252)
if [[ -n "${DPLS_ZMU:-}" ]]; then
  if [[ ! -x "$DPLS_ZMU" ]]; then
    echo "DPLS_ZMU is not executable: $DPLS_ZMU" >&2
    exit 1
  fi
else
  EMU="$ROOT/third_party/phy6252-emu"
  if [[ ! -f "$EMU/Cargo.toml" || ! -f "$EMU/third_party/zmu/Cargo.toml" ]]; then
    git -C "$ROOT" submodule update --init --recursive third_party/phy6252-emu
  fi
  cargo build --manifest-path "$EMU/Cargo.toml" --release
  export DPLS_ZMU="$EMU/target/release/phy6252"
fi
NATIVE="$ROOT/tools/dpls-lab/native"
if [[ ! -x "$NATIVE/dpls-ble" || "$NATIVE/DplsBle.swift" -nt "$NATIVE/dpls-ble" ]]; then
  swiftc -O -framework CoreBluetooth -framework Foundation \
    -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker "$NATIVE/Info.plist" \
    -o "$NATIVE/dpls-ble" "$NATIVE/DplsBle.swift"
fi
cd "$ROOT/tools/dpls-lab"
if [[ ! -d node_modules ]]; then
  bun install
fi
export DPLS_SIMULATOR="$SIM"
exec bun run cli.ts "$@"
