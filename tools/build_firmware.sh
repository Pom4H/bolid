#!/usr/bin/env bash
# Build the PHY6252 Test-DPLS application image.
#
# Application-only (CI / update of an already provisioned device):
#   tools/build_firmware.sh [keil|ac6|gcc] [output.hex]
#
# Personalized build:
#   tools/build_firmware.sh keil output.hex --serial 1234
#   tools/build_firmware.sh keil output.hex --factory-bin device.factory.bin
#
# IMPORTANT: output.hex is ALWAYS an application HEX suitable for programmer
# operation `wh`. Personalization is emitted separately as output.factory.bin;
# it must be written with raw programmer operation `we 0x3F000`. Do not merge
# factory data into the application HEX: `wh` owns the application segment table.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/firmware/targets/phy6252"
TOOLCHAIN="keil"
OUT="$ROOT/tmp/test-dpls.hex"
REGION_ROOT=""
BUILD_LOG=""
SERIAL="${DPLS_SERIAL:-}"
HW_REVISION="${DPLS_HW_REVISION:-2}"
FACTORY_BIN_INPUT="${DPLS_FACTORY_BIN:-}"
STATIC_ADDRESS="${DPLS_STATIC_ADDRESS:-}"
USE_CHIP_PUBLIC=0
OUT_SET=0

usage() {
    cat >&2 <<'EOF'
usage: tools/build_firmware.sh [keil|ac6|gcc] [output.hex] [identity options]

identity options (optional, mutually exclusive source):
  --serial N                    create/reuse output.factory.bin for this serial
  --factory-bin FILE            reuse an existing 64-byte factory identity
  --hw-revision N               factory hardware revision (default: 2)
  --static-address XX:..:XX     use a specified BLE static random address
  --generate-static-address     explicitly request the default generated static address
  --use-chip-public             explicitly rely on a programmed PHY6252 public MAC

New --serial identities use a generated BLE static random address by default.
This keeps provisioning independent of whether a particular PHY6252/PB-03F has
factory public-MAC words programmed. Use --use-chip-public only for a verified
production lot where that address is guaranteed.

With identity options the build emits:
  output.hex                    application image; flash with `wh`
  output.factory.bin            factory identity; flash with `we 0x3F000`
  output.identity.json          non-secret metadata

If output.factory.bin already exists, --serial reuses it after checking the
serial number. It never rotates IRK/CSRK silently. A legacy chip-public sidecar
is rejected unless --use-chip-public is explicitly supplied.

`tools/flash_firmware.sh output.hex` automatically detects the sidecar and uses
both programmer operations in the correct order.
EOF
    exit 2
}

reject_warnings() {
    if grep -E ': warning:|Warning: [LA][0-9]|[1-9][0-9]* warning(s)? generated' "$1"; then
        echo "error: firmware build produced warnings" >&2
        exit 1
    fi
}

factory_serial() {
    python3 - "$1" <<'PY'
import struct, sys
raw = open(sys.argv[1], 'rb').read()
if len(raw) != 64:
    raise SystemExit("factory BIN must be exactly 64 bytes")
print(struct.unpack_from('<I', raw, 8)[0])
PY
}

factory_address_source() {
    python3 - "$1" <<'PY'
import struct, sys
raw = open(sys.argv[1], 'rb').read()
if len(raw) != 64:
    raise SystemExit("factory BIN must be exactly 64 bytes")
flags = struct.unpack_from('<H', raw, 14)[0]
print('static_random' if flags & 0x0001 else 'chip_public')
PY
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help) usage ;;
        keil|ac6|gcc) TOOLCHAIN="$1"; shift ;;
        --serial)
            [ "$#" -ge 2 ] || usage
            SERIAL="$2"; shift 2 ;;
        --factory-bin)
            [ "$#" -ge 2 ] || usage
            FACTORY_BIN_INPUT="$2"; shift 2 ;;
        --hw-revision)
            [ "$#" -ge 2 ] || usage
            HW_REVISION="$2"; shift 2 ;;
        --static-address)
            [ "$#" -ge 2 ] || usage
            STATIC_ADDRESS="$2"; shift 2 ;;
        --generate-static-address)
            USE_CHIP_PUBLIC=0; shift ;;
        --use-chip-public)
            USE_CHIP_PUBLIC=1; shift ;;
        --*) usage ;;
        *)
            if [ "$OUT_SET" -ne 0 ]; then usage; fi
            OUT="$1"; OUT_SET=1; shift ;;
    esac
done
if [ "$TOOLCHAIN" = "ac6" ]; then TOOLCHAIN="keil"; fi

if [ -n "$SERIAL" ] && [ -n "$FACTORY_BIN_INPUT" ]; then
    echo "error: use either --serial or --factory-bin, not both" >&2
    exit 2
fi
if [ -n "$FACTORY_BIN_INPUT" ] && { [ -n "$STATIC_ADDRESS" ] || [ "$USE_CHIP_PUBLIC" -ne 0 ]; }; then
    echo "error: BLE address options cannot modify an existing --factory-bin" >&2
    exit 2
fi
if [ -n "$STATIC_ADDRESS" ] && [ "$USE_CHIP_PUBLIC" -ne 0 ]; then
    echo "error: choose --static-address or --use-chip-public" >&2
    exit 2
fi
case "$SERIAL" in
    ''|*[!0-9]*) [ -z "$SERIAL" ] || { echo "error: serial must be an integer" >&2; exit 2; } ;;
esac
if [ -n "$SERIAL" ] && { [ "$SERIAL" -lt 1 ] || [ "$SERIAL" -gt 4294967294 ]; }; then
    echo "error: serial must be in 1..4294967294" >&2
    exit 2
fi

case "$OUT" in
    /*) ;;
    *) OUT="$ROOT/$OUT" ;;
esac
if [ -n "$FACTORY_BIN_INPUT" ]; then
    case "$FACTORY_BIN_INPUT" in
        /*) ;;
        *) FACTORY_BIN_INPUT="$ROOT/$FACTORY_BIN_INPUT" ;;
    esac
    [ -f "$FACTORY_BIN_INPUT" ] || { echo "error: factory BIN not found: $FACTORY_BIN_INPUT" >&2; exit 1; }
fi

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

    # Exact application-image composition used by hardware-proven 1.4.0.
    # Factory data is never appended here.
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
}

if [ "$TOOLCHAIN" = "gcc" ]; then build_gcc; else build_keil; fi
if [ ! -s "$OUT" ]; then
    echo "error: firmware build did not produce a non-empty HEX: $OUT" >&2
    exit 1
fi

echo "hex: $OUT"
echo "identity: application image only"

if [ -n "$SERIAL" ] || [ -n "$FACTORY_BIN_INPUT" ]; then
    STEM="${OUT%.hex}"
    FACTORY_OUT="$STEM.factory.bin"
    META_OUT="$STEM.identity.json"
    FACTORY_ARGS=(--binary-output "$FACTORY_OUT" --metadata "$META_OUT")

    if [ -n "$FACTORY_BIN_INPUT" ]; then
        FACTORY_ARGS+=(--record-input "$FACTORY_BIN_INPUT")
        IDENTITY_ACTION="reused explicit factory BIN"
    elif [ -f "$FACTORY_OUT" ]; then
        EXISTING_SERIAL="$(factory_serial "$FACTORY_OUT")"
        if [ "$EXISTING_SERIAL" != "$SERIAL" ]; then
            echo "error: $FACTORY_OUT belongs to serial=$EXISTING_SERIAL, requested serial=$SERIAL" >&2
            exit 2
        fi
        EXISTING_SOURCE="$(factory_address_source "$FACTORY_OUT")"
        if [ "$EXISTING_SOURCE" = "chip_public" ] && [ "$USE_CHIP_PUBLIC" -eq 0 ]; then
            echo "error: $FACTORY_OUT relies on a PHY6252 factory public MAC" >&2
            echo "remove/rename the old sidecar to generate a static identity, or pass --use-chip-public only for verified hardware" >&2
            exit 2
        fi
        if [ "$EXISTING_SOURCE" = "static_random" ] && [ "$USE_CHIP_PUBLIC" -ne 0 ]; then
            echo "error: $FACTORY_OUT already owns a static BLE address; do not switch address source silently" >&2
            exit 2
        fi
        FACTORY_ARGS+=(--record-input "$FACTORY_OUT")
        IDENTITY_ACTION="reused existing sidecar"
    else
        FACTORY_ARGS+=(--serial "$SERIAL" --hw-revision "$HW_REVISION")
        if [ -n "$STATIC_ADDRESS" ]; then
            FACTORY_ARGS+=(--static-address "$STATIC_ADDRESS")
            IDENTITY_ACTION="created new identity with explicit static BLE address"
        elif [ "$USE_CHIP_PUBLIC" -ne 0 ]; then
            IDENTITY_ACTION="created new identity using verified chip public MAC"
        else
            FACTORY_ARGS+=(--generate-static-address)
            IDENTITY_ACTION="created new identity with generated static BLE address"
        fi
    fi

    python3 "$ROOT/tools/make_factory_identity.py" "${FACTORY_ARGS[@]}"
    [ -s "$FACTORY_OUT" ] || { echo "error: factory identity sidecar was not produced" >&2; exit 1; }
    echo "factory-bin: $FACTORY_OUT ($IDENTITY_ACTION)"
    echo "identity-meta: $META_OUT"
    echo "flash: tools/flash_firmware.sh $OUT"
else
    echo "NOTE: no factory sidecar was emitted; this image only advertises on an already provisioned board."
fi
