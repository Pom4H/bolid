#!/usr/bin/env bash
# Build two otherwise-identical GNU Arm images for PB-03F current measurements:
#   A: connected sleep enabled (RC9 candidate)
#   B: historical link-wide MOD_USR0 guard
#
# Absolute release current should be measured with the normal AC6 production
# image. This pair isolates the connected-sleep delta without source edits.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-$ROOT/tmp/power-ab}"
mkdir -p "$OUT_DIR"

build_variant() {
    local name="$1" connected_sleep="$2" build_dir="$3"
    local out="$OUT_DIR/TestDPLS-1.4.2-rc9-${name}.hex"

    echo "==> $name: DPLS_CONNECTED_SLEEP=$connected_sleep DEBUG_INFO=0"
    BUILD="$build_dir" \
    DPLS_CONNECTED_SLEEP="$connected_sleep" \
    DEBUG_INFO=0 \
        bash "$ROOT/tools/build_firmware.sh" gcc "$out"
    sha256sum "$out" > "$out.sha256"
}

build_variant low-power 1 build-power-low
build_variant link-guard 0 build-power-guard

cat > "$OUT_DIR/BUILD.txt" <<EOF
version=1.4.2
rc=9
commit=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || printf unknown)
toolchain=GNU-Arm-13.2.1
DEBUG_INFO=0
low-power.DPLS_CONNECTED_SLEEP=1
link-guard.DPLS_CONNECTED_SLEEP=0
purpose=PB-03F connected-sleep current/reliability A-B
EOF

printf '\nA/B images:\n'
printf '  %s\n' "$OUT_DIR/TestDPLS-1.4.2-rc9-low-power.hex"
printf '  %s\n' "$OUT_DIR/TestDPLS-1.4.2-rc9-link-guard.hex"
printf '  %s\n' "$OUT_DIR/BUILD.txt"
