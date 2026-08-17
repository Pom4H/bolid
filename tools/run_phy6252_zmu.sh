#!/usr/bin/env bash
# Run a PHY6252 Intel HEX on phy6252 (guest from third_party/phy6252-emu).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EMU="$ROOT/third_party/phy6252-emu"
HEX="${1:-${DPLS_HEX:-$EMU/firmware/kit-demo.hex}}"
shift || true

if [[ -n "${DPLS_ZMU:-}" ]]; then
  if [[ ! -x "$DPLS_ZMU" ]]; then
    echo "DPLS_ZMU is not executable: $DPLS_ZMU" >&2
    exit 1
  fi
  exec "$DPLS_ZMU" --raw "$HEX" "$@"
fi

if [[ ! -f "$EMU/Cargo.toml" || ! -f "$EMU/third_party/zmu/Cargo.toml" ]]; then
  git -C "$ROOT" submodule update --init --recursive third_party/phy6252-emu
fi

exec cargo run --manifest-path "$EMU/Cargo.toml" --release -- --raw "$HEX" "$@"
