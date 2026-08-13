#!/usr/bin/env bash
# Build Test-DPLS with the full pinned PHY62XX SDK 3.1.2 and Keil/Arm Compiler 6,
# then combine the load regions into a flashable Intel HEX file.
#
#   tools/build_firmware_keil.sh [output.hex]
#
# ADC line/reserve voltage sampling is selected by the DPLS_ADC environment
# variable (default 1 = enabled):
#
#   DPLS_ADC=1 tools/build_firmware_keil.sh tmp/test-dpls-sdk-3.1.2.hex
#   DPLS_ADC=0 tools/build_firmware_keil.sh tmp/test-dpls-adcoff.hex
#
# Activate Firmware/targets/phy6252/vcpkg-configuration.json first, or run the
# GitHub Actions Keil target workflow which does that automatically.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/Firmware/targets/phy6252"
OUT="${1:-$ROOT/tmp/test-dpls-sdk-3.1.2.hex}"
DPLS_ADC="${DPLS_ADC:-1}"
APP_SOURCE="$ROOT/Firmware/phy6252/dpls_phy6252_app.c"
APP_BACKUP=""
REGION_ROOT=""

cleanup() {
    if [ -n "$APP_BACKUP" ] && [ -f "$APP_BACKUP" ]; then
        cp "$APP_BACKUP" "$APP_SOURCE"
        rm -f "$APP_BACKUP"
    fi
    if [ -n "$REGION_ROOT" ]; then
        rm -rf "$REGION_ROOT"
    fi
}
trap cleanup EXIT

bash "$ROOT/tools/fetch_phy6252_sdk.sh"

for tool in cbuild fromelf python3; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "$tool not found. Activate the Arm vcpkg environment from:" >&2
        echo "  $TARGET/vcpkg-configuration.json" >&2
        exit 1
    fi
done

if [ "$DPLS_ADC" = "1" ]; then
    echo "ADC sampling: ENABLED"
    APP_BACKUP="$(mktemp)"
    cp "$APP_SOURCE" "$APP_BACKUP"
    python3 "$ROOT/tools/prepare_phy6252_sdk312_app.py" "$APP_SOURCE"
else
    echo "ADC sampling: DISABLED"
fi

rm -rf "$TARGET/out" "$TARGET/tmp" "$TARGET/RTE"
mkdir -p "$TARGET/out"
ln -s ../../../sdk "$TARGET/out/sdk"
cbuild "$TARGET/test-dpls.csolution.yml" --packs --update-rte

AXF="$(find "$TARGET/out" -type f -name '*.axf' | head -n 1)"
if [ -z "$AXF" ] || [ ! -f "$AXF" ]; then
    echo "Target build completed without an AXF output" >&2
    exit 1
fi

REGION_ROOT="$(mktemp -d)"
REGIONS="$REGION_ROOT/regions"
fromelf --i32 --output "$REGIONS" "$AXF"

for region in ER_ROM_XIP JUMP_TABLE ER_IROM1; do
    if [ ! -f "$REGIONS/$region" ]; then
        echo "Missing fromelf region: $region" >&2
        exit 1
    fi
done

mkdir -p "$(dirname "$OUT")"
grep -v '^:00000001FF' "$REGIONS/ER_ROM_XIP" | grep -v '^:04000005' > "$OUT"
grep -v '^:00000001FF' "$REGIONS/JUMP_TABLE" | grep -v '^:04000005' >> "$OUT"
cat "$REGIONS/ER_IROM1" >> "$OUT"

echo "axf: $AXF"
echo "hex: $OUT"
