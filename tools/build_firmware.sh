#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/Firmware/targets/phy6252"
OUT="${1:-$ROOT/tmp/test-dpls-sdk-3.1.2.hex}"
DPLS_ADC="${DPLS_ADC:-1}"
APP_SOURCE="$ROOT/Firmware/phy6252/dpls_phy6252_app.c"
APP_BACKUP=""

cleanup() {
    if [ -n "$APP_BACKUP" ] && [ -f "$APP_BACKUP" ]; then
        cp "$APP_BACKUP" "$APP_SOURCE"
        rm -f "$APP_BACKUP"
    fi
}
trap cleanup EXIT

bash "$ROOT/tools/fetch_phy6252_sdk.sh"

for tool in make arm-none-eabi-gcc arm-none-eabi-objcopy python3; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "$tool not found" >&2
        exit 1
    }
done

if [ "$DPLS_ADC" = "1" ]; then
    echo "ADC sampling: ENABLED"
    APP_BACKUP="$(mktemp)"
    cp "$APP_SOURCE" "$APP_BACKUP"
    python3 "$ROOT/tools/prepare_phy6252_sdk312_app.py" "$APP_SOURCE"
else
    echo "ADC sampling: DISABLED"
fi

make -C "$TARGET" clean all

BUILT_HEX="$TARGET/out/test-dpls.hex"
BUILT_ELF="$TARGET/out/test-dpls.elf"
test -f "$BUILT_HEX"
test -f "$BUILT_ELF"

mkdir -p "$(dirname "$OUT")"
cp "$BUILT_HEX" "$OUT"

echo "elf: $BUILT_ELF"
echo "hex: $OUT"
