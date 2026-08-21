#!/usr/bin/env bash
# Сборка одного application HEX для PHY6252.
#
#   tools/build_firmware.sh [keil|gcc] [output.hex]
#
# Для Keil/AC6 скрипт сам поднимает pinned vcpkg, CMSIS-Toolbox,
# Arm Compiler 6 и локальную лицензию. Ручные export PATH не нужны.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/firmware/targets/phy6252"
TOOLCHAIN="keil"
OUT="$ROOT/tmp/test-dpls.hex"
REGION_ROOT=""
BUILD_LOG=""
OUT_SET=0
VCPKG_VERSION="2026.04.27"
VCPKG_ENV_JSON=""

usage() {
    local status="${1:-2}"
    cat >&2 <<'EOF'
usage: tools/build_firmware.sh [keil|gcc] [output.hex]

Defaults:
  toolchain: keil (Arm Compiler 6.24.0)
  output:    tmp/test-dpls.hex

Existing commercial/user Arm license:
  ARM_LICENSE_CODE=<activation-code>
  or ARM_LICENSE_PRODUCT=<product> ARM_LICENSE_SERVER=<url>
EOF
    exit "$status"
}

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "error: required host command not found: $1" >&2
        exit 1
    }
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
        -h|--help) usage 0 ;;
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
    if [ -n "$VCPKG_ENV_JSON" ]; then rm -f "$VCPKG_ENV_JSON"; fi
}
trap cleanup EXIT

need git
need python3
bash "$ROOT/tools/fetch_phy6252_sdk.sh"
mkdir -p "$(dirname "$OUT")" "$ROOT/tmp" "$ROOT/.toolchains"

keil_tools_ready() {
    command -v cbuild >/dev/null 2>&1 || return 1
    command -v fromelf >/dev/null 2>&1 || return 1
    command -v armclang >/dev/null 2>&1 || return 1
    command -v armlm >/dev/null 2>&1 || return 1
    armclang --version 2>/dev/null | grep -q '6\.24'
}

ensure_macos_rosetta() {
    [ "$(uname -s)" = "Darwin" ] || return 0
    [ "$(uname -m)" = "arm64" ] || return 0

    # AC6 6.24 для macOS поставляется Arm как Intel/x86_64 binary.
    if /usr/bin/arch -x86_64 /usr/bin/true >/dev/null 2>&1; then
        return 0
    fi

    echo "==> Installing Rosetta 2 for Arm Compiler 6"
    /usr/sbin/softwareupdate --install-rosetta --agree-to-license || {
        echo "error: Rosetta 2 is required to run Arm Compiler 6.24.0 on Apple Silicon" >&2
        exit 1
    }
}

bootstrap_vcpkg() {
    local dir="$ROOT/.toolchains/vcpkg-$VCPKG_VERSION"
    if [ ! -x "$dir/vcpkg" ]; then
        echo "==> Installing pinned vcpkg $VCPKG_VERSION" >&2
        rm -rf "$dir"
        git clone --quiet --depth 1 --branch "$VCPKG_VERSION" \
            https://github.com/microsoft/vcpkg.git "$dir"
        "$dir/bootstrap-vcpkg.sh" -disableMetrics >&2
    fi
    printf '%s\n' "$dir/vcpkg"
}

activate_vcpkg_keil() {
    local vcpkg
    local downloads="$ROOT/.toolchains/vcpkg-downloads"
    local path_additions

    vcpkg="$(bootstrap_vcpkg)"
    mkdir -p "$downloads"
    VCPKG_ENV_JSON="$(mktemp "$ROOT/tmp/vcpkg-env.XXXXXX")"

    echo "==> Activating CMSIS-Toolbox 2.14.1 + Arm Compiler 6.24.0"
    if ! (
        cd "$TARGET"
        "$vcpkg" activate --downloads-root="$downloads" --json="$VCPKG_ENV_JSON"
    ); then
        echo "==> Refreshing Arm vcpkg registry"
        (
            cd "$TARGET"
            "$vcpkg" x-update-registry --all
            "$vcpkg" activate --downloads-root="$downloads" --json="$VCPKG_ENV_JSON"
        )
    fi

    path_additions="$(python3 - "$VCPKG_ENV_JSON" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    env = json.load(f)
print(":".join(env.get("paths", {}).get("PATH", [])))
PY
)"
    if [ -n "$path_additions" ]; then
        export PATH="$path_additions:$PATH"
    fi

    eval "$(python3 - "$VCPKG_ENV_JSON" <<'PY'
import json, re, shlex, sys
with open(sys.argv[1], encoding="utf-8") as f:
    env = json.load(f)
for key, value in env.get("tools", {}).items():
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
        print(f"export {key}={shlex.quote(str(value))}")
PY
)"
}

activate_arm_license() {
    local current=""
    local product=""
    current="$(armlm inspect 2>/dev/null || true)"
    product="$(printf '%s\n' "$current" | sed -n 's/^[[:space:]]*Product code: \(KEMDK-[A-Z0-9]*\).*/\1/p' | head -n 1)"

    # Уже известную MDK-лицензию используем; истёкший локальный cache обновляем.
    if [ -n "$product" ]; then
        if printf '%s\n' "$current" | grep -qi 'expired'; then
            echo "==> Refreshing Arm license cache for $product"
            armlm reactivate --product "$product"
        else
            echo "==> Arm license already active: $product"
        fi
        return 0
    fi

    if [ -n "${ARM_LICENSE_CODE:-}" ]; then
        echo "==> Activating Arm license from ARM_LICENSE_CODE"
        armlm activate --code "$ARM_LICENSE_CODE"
        return 0
    fi

    if [ -n "${ARM_LICENSE_PRODUCT:-}" ] || [ -n "${ARM_LICENSE_SERVER:-}" ]; then
        if [ -z "${ARM_LICENSE_PRODUCT:-}" ] || [ -z "${ARM_LICENSE_SERVER:-}" ]; then
            echo "error: set both ARM_LICENSE_PRODUCT and ARM_LICENSE_SERVER" >&2
            exit 1
        fi
        echo "==> Activating Arm license product $ARM_LICENSE_PRODUCT"
        armlm activate --product "$ARM_LICENSE_PRODUCT" --server "$ARM_LICENSE_SERVER"
        return 0
    fi

    echo "==> Activating Keil MDK Community (KEMDK-COM0, non-commercial)"
    armlm activate --product KEMDK-COM0 --server https://mdk-preview.keil.arm.com
}

ensure_keil_environment() {
    # Уже активированное окружение используем без повторной загрузки.
    if ! keil_tools_ready; then
        activate_vcpkg_keil
    fi

    ensure_macos_rosetta

    # CMSIS-Toolbox выбирает AC6 по versioned environment variable.
    if [ -z "${AC6_TOOLCHAIN_6_24_0:-}" ]; then
        export AC6_TOOLCHAIN_6_24_0="$(dirname "$(command -v armclang)")"
    fi

    for tool in cbuild fromelf armclang armlm; do
        command -v "$tool" >/dev/null 2>&1 || {
            echo "error: $tool not found after AC6 bootstrap" >&2
            exit 1
        }
    done
    armclang --version 2>/dev/null | grep -q '6\.24' || {
        echo "error: Arm Compiler 6.24.0 required" >&2
        exit 1
    }

    activate_arm_license
    echo "compiler: $(command -v armclang)"
    echo "cmsis:   $(command -v cbuild)"
    echo "AC6_TOOLCHAIN_6_24_0=$AC6_TOOLCHAIN_6_24_0"
}

build_keil() {
    ensure_keil_environment

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

    # Application HEX содержит XIP image и две SRAM load regions.
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
echo "auto reset: tools/flash_firmware.sh $OUT --auto-rst"
