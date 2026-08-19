#!/usr/bin/env bash
# Build the PHY6252 Test-DPLS image.
#
#   tools/build_firmware.sh [keil|ac6|gcc] [output.hex]
#
# keil/ac6 (default) — Keil MDK Community / Arm Compiler 6 via cbuild.
# gcc                 — GNU Arm Embedded, same sources and memory map.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/firmware/targets/phy6252"
TOOLCHAIN="keil"
OUT="$ROOT/tmp/test-dpls.hex"
REGION_ROOT=""
BUILD_LOG=""

usage() {
    echo "usage: tools/build_firmware.sh [keil|ac6|gcc] [output.hex]" >&2
    exit 2
}

reject_warnings() {
    if grep -E ': warning:|Warning: [LA][0-9]|[1-9][0-9]* warning(s)? generated' "$1"; then
        echo "error: firmware build produced warnings" >&2
        exit 1
    fi
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help) usage ;;
        keil|ac6|gcc) TOOLCHAIN="$1"; shift ;;
        *) OUT="$1"; shift ;;
    esac
done
if [ "$TOOLCHAIN" = "ac6" ]; then TOOLCHAIN="keil"; fi

# Callers normally pass repo-relative paths such as tmp/test-dpls.hex. GCC is
# invoked with `make -C "$TARGET"`, so leaving OUT relative would make objcopy
# write under firmware/targets/phy6252 instead of the caller's workspace.
case "$OUT" in
    /*) ;;
    *) OUT="$ROOT/$OUT" ;;
esac

cleanup() {
    if [ -n "$REGION_ROOT" ]; then rm -rf "$REGION_ROOT"; fi
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
    mkdir -p "$TARGET/out" "$(dirname "$OUT")" "$ROOT/tmp"
    ln -s ../../../sdk "$TARGET/out/sdk"
    BUILD_LOG="$ROOT/tmp/firmware-keil.log"
    cbuild "$TARGET/test-dpls.csolution.yml" --packs --update-rte 2>&1 | tee "$BUILD_LOG"
    reject_warnings "$BUILD_LOG"

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

    grep -v '^:00000001FF' "$REGIONS/ER_ROM_XIP" | grep -v '^:04000005' > "$OUT"
    grep -v '^:00000001FF' "$REGIONS/JUMP_TABLE" | grep -v '^:04000005' >> "$OUT"
    cat "$REGIONS/ER_IROM1" >> "$OUT"

    echo "toolchain: Keil MDK / Arm Compiler 6"
    echo "axf: $AXF"
    echo "hex: $OUT"
}

gcc_has_target_headers() {
    command -v arm-none-eabi-gcc >/dev/null 2>&1 || return 1
    printf '#include <stdint.h>\nint main(void){return 0;}\n' | \
        arm-none-eabi-gcc -mcpu=cortex-m0 -mthumb -x c -E - >/dev/null 2>&1
}

activate_pinned_gcc() {
    if gcc_has_target_headers; then
        return 0
    fi

    if command -v arm-none-eabi-gcc >/dev/null 2>&1; then
        echo "System arm-none-eabi-gcc is incomplete (target stdint.h/newlib unavailable)." >&2
        echo "Using pinned Arm GNU Toolchain 13.2.rel1 instead." >&2
    else
        echo "arm-none-eabi-gcc not found; downloading pinned Arm GNU Toolchain 13.2.rel1." >&2
    fi

    local bin
    bin="$(bash "$ROOT/tools/fetch_arm_gcc.sh")"
    export PATH="$bin:$PATH"

    if ! gcc_has_target_headers; then
        echo "Pinned Arm GNU Toolchain is unusable: target standard headers are unavailable" >&2
        exit 1
    fi
}

build_gcc() {
    activate_pinned_gcc
    for tool in arm-none-eabi-gcc arm-none-eabi-objcopy; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            echo "$tool not found after Arm GNU Toolchain setup" >&2
            exit 1
        fi
    done

    echo "compiler: $(command -v arm-none-eabi-gcc)"
    arm-none-eabi-gcc --version | head -n 1

    BUILD_LOG="$ROOT/tmp/firmware-gcc.log"
    make -C "$TARGET" HEX="$OUT" CROSS=arm-none-eabi- 2>&1 | tee "$BUILD_LOG"
    reject_warnings "$BUILD_LOG"
    echo "toolchain: GNU Arm Embedded"
    echo "elf: $TARGET/build/test-dpls.elf"
    echo "hex: $OUT"
}

if [ "$TOOLCHAIN" = "gcc" ]; then build_gcc; else build_keil; fi
if [ ! -s "$OUT" ]; then
    echo "error: firmware build did not produce a non-empty HEX: $OUT" >&2
    exit 1
fi
