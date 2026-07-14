#!/usr/bin/env bash
# Build Test-DPLS with the full pinned PHY62XX SDK 3.1.2 and Arm Compiler 6,
# then combine the load regions into a flashable Intel HEX file.
#
#   tools/build_firmware.sh [output.hex]
#
# Activate Firmware/targets/phy6252/vcpkg-configuration.json first, or run the
# GitHub Actions target workflow which does that automatically.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/Firmware/targets/phy6252"
OUT="${1:-$ROOT/tmp/test-dpls-sdk-3.1.2.hex}"

bash "$ROOT/tools/fetch_phy6252_sdk.sh"

for tool in cbuild fromelf; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "$tool not found. Activate the Arm vcpkg environment from:" >&2
        echo "  $TARGET/vcpkg-configuration.json" >&2
        exit 1
    fi
done

rm -rf "$TARGET/out" "$TARGET/tmp" "$TARGET/RTE"
# CMSIS Toolbox resolves free-form linker input paths relative to the generated
# output directory. Keep a deterministic bridge to Firmware/sdk so the vendor
# ROM symbol table is found without machine-specific absolute paths.
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
trap 'rm -rf "$REGION_ROOT"' EXIT
fromelf --i32 --output "$REGIONS" "$AXF"

for region in ER_ROM_XIP JUMP_TABLE ER_IROM1; do
    if [ ! -f "$REGIONS/$region" ]; then
        echo "Missing fromelf region: $region" >&2
        exit 1
    fi
done

mkdir -p "$(dirname "$OUT")"
# fromelf emits an entry-point record in every region. The PHY62x2 flasher stops
# parsing at the first such record, so retain it only in the final ER_IROM1 part.
grep -v '^:00000001FF' "$REGIONS/ER_ROM_XIP" | grep -v '^:04000005' > "$OUT"
grep -v '^:00000001FF' "$REGIONS/JUMP_TABLE" | grep -v '^:04000005' >> "$OUT"
cat "$REGIONS/ER_IROM1" >> "$OUT"

echo "axf: $AXF"
echo "hex: $OUT"
