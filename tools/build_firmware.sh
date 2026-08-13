#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/Firmware/targets/phy6252"
OUT="${1:-$ROOT/tmp/test-dpls-sdk-3.1.2.hex}"
APP="$ROOT/Firmware/phy6252/dpls_phy6252_app.c"
BACKUP="$APP.gcc-backup"

restore_app() {
    if [ -f "$BACKUP" ]; then
        mv "$BACKUP" "$APP"
    fi
}
trap restore_app EXIT

bash "$ROOT/tools/fetch_phy6252_sdk.sh"

if [ "${DPLS_ADC:-1}" = "1" ]; then
    cp "$APP" "$BACKUP"
    python3 "$ROOT/tools/prepare_phy6252_sdk312_app.py" "$APP"
fi

make -C "$TARGET" clean all
mkdir -p "$(dirname "$OUT")"
cp "$TARGET/build/test-dpls.hex" "$OUT"
mkdir -p "$TARGET/out/test-dpls/gcc"
cp "$TARGET/build/test-dpls.elf" "$TARGET/out/test-dpls/gcc/test-dpls.axf"
cp "$TARGET/build/test-dpls.map" "$TARGET/out/test-dpls/gcc/test-dpls.axf.map"
