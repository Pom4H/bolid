#!/usr/bin/env bash
# Build the PHY6252 Test-DPLS image.
#
#   tools/build_firmware.sh [keil|ac6|gcc] [output.hex]
#
# keil/ac6 (default) — Keil MDK Community / Arm Compiler 6 via cbuild.
# gcc                 — GNU Arm Embedded, same sources and memory map.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/Firmware/targets/phy6252"
TOOLCHAIN="keil"
OUT="$ROOT/tmp/test-dpls.hex"
REGION_ROOT=""

usage() {
    echo "usage: tools/build_firmware.sh [keil|ac6|gcc] [output.hex]" >&2
    exit 2
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help) usage ;;
        keil|ac6|gcc)
            TOOLCHAIN="$1"
            shift
            ;;
        *)
            OUT="$1"
            shift
            ;;
    esac
done
if [ "$TOOLCHAIN" = "ac6" ]; then
    TOOLCHAIN="keil"
fi

cleanup() {
    if [ -n "$REGION_ROOT" ]; then
        rm -rf "$REGION_ROOT"
    fi
}
trap cleanup EXIT

bash "$ROOT/tools/fetch_phy6252_sdk.sh"
mkdir -p "$(dirname "$OUT")"

build_keil() {
    for tool in cbuild fromelf; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            echo "$tool not found. Activate Keil MDK / Arm Compiler 6 from:" >&2
            echo "  $TARGET/vcpkg-configuration.json" >&2
            exit 1
        fi
    done

    rm -rf "$TARGET/out" "$TARGET/tmp" "$TARGET/RTE"
    mkdir -p "$TARGET/out"
    ln -s ../../../sdk "$TARGET/out/sdk"
    cbuild "$TARGET/test-dpls.csolution.yml" --packs --update-rte

    AXF="$(find "$TARGET/out" -type f -name '*.axf' | head -n 1)"
    if [ -z "$AXF" ] || [ ! -f "$AXF" ]; then
        echo "Keil/AC6 target build completed without an AXF output" >&2
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

    # fromelf emits an entry-point record in every region. The PHY62x2 flasher
    # stops parsing at the first such record, so keep it only on ER_IROM1.
    grep -v '^:00000001FF' "$REGIONS/ER_ROM_XIP" | grep -v '^:04000005' > "$OUT"
    grep -v '^:00000001FF' "$REGIONS/JUMP_TABLE" | grep -v '^:04000005' >> "$OUT"
    cat "$REGIONS/ER_IROM1" >> "$OUT"

    echo "toolchain: Keil MDK / Arm Compiler 6"
    echo "axf: $AXF"
    echo "hex: $OUT"
}

build_gcc() {
    for tool in arm-none-eabi-gcc arm-none-eabi-objcopy; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            echo "$tool not found. Install gcc-arm-none-eabi." >&2
            exit 1
        fi
    done
    make -C "$TARGET" HEX="$OUT"
    echo "toolchain: GNU Arm Embedded"
    echo "elf: $TARGET/build/test-dpls.elf"
    echo "hex: $OUT"
}

if [ "$TOOLCHAIN" = "gcc" ]; then
    build_gcc
else
    build_keil
fi
