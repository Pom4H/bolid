#!/usr/bin/env bash
# Сборка одного application HEX для PHY6252.
#
#   tools/build_firmware.sh [keil|ac6|gcc] [output.hex]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/firmware/targets/phy6252"
TOOLCHAIN="keil"
OUT="$ROOT/tmp/test-dpls.hex"
REGION_ROOT=""
BUILD_LOG=""
OUT_SET=0

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

check_hex_layout() {
    python3 - "$1" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
upper = 0
segment = 0
snv_start = 0x1103C000
snv_end = 0x11040000
max_xip_end = 0

for number, raw_line in enumerate(path.read_text(encoding="ascii").splitlines(), start=1):
    line = raw_line.strip()
    if not line:
        continue
    if not line.startswith(":"):
        raise SystemExit(f"{path}: invalid Intel HEX line {number}")
    record = bytes.fromhex(line[1:])
    if len(record) < 5 or len(record) != record[0] + 5 or sum(record) & 0xFF:
        raise SystemExit(f"{path}: invalid Intel HEX checksum/length at line {number}")
    length = record[0]
    offset = (record[1] << 8) | record[2]
    kind = record[3]
    data = record[4:4 + length]
    if kind == 0x04:
        if length != 2:
            raise SystemExit(f"{path}: invalid extended linear address at line {number}")
        upper = ((data[0] << 8) | data[1]) << 16
        segment = 0
    elif kind == 0x02:
        if length != 2:
            raise SystemExit(f"{path}: invalid extended segment address at line {number}")
        segment = ((data[0] << 8) | data[1]) << 4
        upper = 0
    elif kind == 0x00:
        start = upper + segment + offset
        end = start + length
        if start < snv_end and end > snv_start:
            raise SystemExit(
                f"{path}: application HEX overlaps SNV: 0x{start:08X}..0x{end - 1:08X}"
            )
        if 0x11020000 <= start < 0x11040000:
            max_xip_end = max(max_xip_end, end)

if max_xip_end:
    print(f"XIP image end: 0x{max_xip_end:08X}; SNV starts: 0x{snv_start:08X}")
PY
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help) usage ;;
        keil|ac6|gcc) TOOLCHAIN="$1"; shift ;;
        --*) usage ;;
        *)
            if [ "$OUT_SET" -ne 0 ]; then usage; fi
            OUT="$1"; OUT_SET=1; shift ;;
    esac
done
if [ "$TOOLCHAIN" = "ac6" ]; then TOOLCHAIN="keil"; fi

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

    # Application HEX contains the XIP image plus the two SRAM load regions.
    grep -v '^:00000001FF' "$REGIONS/ER_ROM_XIP" | grep -v '^:04000005' > "$OUT"
    grep -v '^:00000001FF' "$REGIONS/JUMP_TABLE" | grep -v '^:04000005' >> "$OUT"
    cat "$REGIONS/ER_IROM1" >> "$OUT"

    echo "toolchain: Keil MDK / Arm Compiler 6"
    echo "axf: $AXF"
}

gcc_has_target_headers() {
    command -v arm-none-eabi-gcc >/dev/null 2>&1 || return 1
    printf '#include <stdint.h>\nint main(void){return 0;}\n' | \
        arm-none-eabi-gcc -mcpu=cortex-m0 -mthumb -x c -E - >/dev/null 2>&1
}

activate_pinned_gcc() {
    if gcc_has_target_headers; then return 0; fi

    if command -v arm-none-eabi-gcc >/dev/null 2>&1; then
        echo "System arm-none-eabi-gcc is incomplete; using pinned 13.2.rel1." >&2
    else
        echo "arm-none-eabi-gcc not found; downloading pinned 13.2.rel1." >&2
    fi

    local bin
    bin="$(bash "$ROOT/tools/fetch_arm_gcc.sh")"
    export PATH="$bin:$PATH"

    if ! gcc_has_target_headers; then
        echo "Pinned Arm GNU Toolchain is unusable" >&2
        exit 1
    fi
}

build_gcc() {
    activate_pinned_gcc
    for tool in arm-none-eabi-gcc arm-none-eabi-objcopy; do
        command -v "$tool" >/dev/null 2>&1 || { echo "$tool not found" >&2; exit 1; }
    done

    echo "compiler: $(command -v arm-none-eabi-gcc)"
    arm-none-eabi-gcc --version | head -n 1

    BUILD_LOG="$ROOT/tmp/firmware-gcc.log"
    make -C "$TARGET" HEX="$OUT" CROSS=arm-none-eabi- 2>&1 | tee "$BUILD_LOG"
    reject_warnings "$BUILD_LOG"
    echo "toolchain: GNU Arm Embedded"
    echo "elf: $TARGET/build/test-dpls.elf"
}

if [ "$TOOLCHAIN" = "gcc" ]; then build_gcc; else build_keil; fi
if [ ! -s "$OUT" ]; then
    echo "error: firmware build did not produce a non-empty HEX: $OUT" >&2
    exit 1
fi
check_hex_layout "$OUT"

echo "hex: $OUT"
echo "flash: tools/flash_firmware.sh $OUT"
