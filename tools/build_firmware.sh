#!/usr/bin/env bash
# Build the PHY6252 Test-DPLS image.
#
# Application-only (for CI / updates of an already provisioned device):
#   tools/build_firmware.sh [keil|ac6|gcc] [output.hex]
#
# First flash / personalization (application + factory identity in one HEX):
#   tools/build_firmware.sh keil output.hex --serial 1234
#   tools/build_firmware.sh keil output.hex --factory-bin device-1234.factory.bin
#
# --serial creates a NEW identity once. Keep the emitted .factory.bin safe and
# reuse it with --factory-bin for reproducible reflashes; do not regenerate keys
# for an already paired production device.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/firmware/targets/phy6252"
TOOLCHAIN="keil"
FINAL_OUT="$ROOT/tmp/test-dpls.hex"
REGION_ROOT=""
BUILD_LOG=""
SERIAL="${DPLS_SERIAL:-}"
HW_REVISION="${DPLS_HW_REVISION:-2}"
FACTORY_BIN_INPUT="${DPLS_FACTORY_BIN:-}"
STATIC_ADDRESS="${DPLS_STATIC_ADDRESS:-}"
GENERATE_STATIC_ADDRESS=0
OUT_SET=0

usage() {
    cat >&2 <<'EOF'
usage: tools/build_firmware.sh [keil|ac6|gcc] [output.hex] [identity options]

identity options (optional, mutually exclusive source):
  --serial N                    create a new factory identity and flash-ready HEX
  --factory-bin FILE            reuse an existing 64-byte factory identity
  --hw-revision N               factory hardware revision (default: 2)
  --static-address XX:..:XX     use a specified BLE static random address
  --generate-static-address     generate a BLE static random address

Without --serial/--factory-bin the output is application-only and requires an
already provisioned factory sector on the board.
EOF
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
            GENERATE_STATIC_ADDRESS=1; shift ;;
        --*) usage ;;
        *)
            if [ "$OUT_SET" -ne 0 ]; then usage; fi
            FINAL_OUT="$1"; OUT_SET=1; shift ;;
    esac
done
if [ "$TOOLCHAIN" = "ac6" ]; then TOOLCHAIN="keil"; fi

if [ -n "$SERIAL" ] && [ -n "$FACTORY_BIN_INPUT" ]; then
    echo "error: use either --serial or --factory-bin, not both" >&2
    exit 2
fi
if [ -n "$FACTORY_BIN_INPUT" ] && { [ -n "$STATIC_ADDRESS" ] || [ "$GENERATE_STATIC_ADDRESS" -ne 0 ]; }; then
    echo "error: BLE address options cannot modify an existing --factory-bin" >&2
    exit 2
fi
if [ -n "$STATIC_ADDRESS" ] && [ "$GENERATE_STATIC_ADDRESS" -ne 0 ]; then
    echo "error: choose --static-address or --generate-static-address" >&2
    exit 2
fi

case "$FINAL_OUT" in
    /*) ;;
    *) FINAL_OUT="$ROOT/$FINAL_OUT" ;;
esac
if [ -n "$FACTORY_BIN_INPUT" ]; then
    case "$FACTORY_BIN_INPUT" in
        /*) ;;
        *) FACTORY_BIN_INPUT="$ROOT/$FACTORY_BIN_INPUT" ;;
    esac
    [ -f "$FACTORY_BIN_INPUT" ] || { echo "error: factory BIN not found: $FACTORY_BIN_INPUT" >&2; exit 1; }
fi

PERSONALIZED=0
if [ -n "$SERIAL" ] || [ -n "$FACTORY_BIN_INPUT" ]; then PERSONALIZED=1; fi
if [ "$PERSONALIZED" -eq 1 ]; then
    APP_OUT="${FINAL_OUT%.hex}.application.tmp.hex"
else
    APP_OUT="$FINAL_OUT"
fi
OUT="$APP_OUT"

cleanup() {
    if [ -n "$REGION_ROOT" ]; then rm -rf "$REGION_ROOT"; fi
    if [ "$PERSONALIZED" -eq 1 ]; then rm -f "$APP_OUT"; fi
}
trap cleanup EXIT

bash "$ROOT/tools/fetch_phy6252_sdk.sh"
mkdir -p "$(dirname "$FINAL_OUT")"

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
if [ ! -s "$APP_OUT" ]; then
    echo "error: firmware build did not produce a non-empty HEX: $APP_OUT" >&2
    exit 1
fi

if [ "$PERSONALIZED" -eq 1 ]; then
    STEM="${FINAL_OUT%.hex}"
    FACTORY_OUT="$STEM.factory.bin"
    META_OUT="$STEM.identity.json"
    FACTORY_ARGS=(
        --binary-output "$FACTORY_OUT"
        --merge-app-hex "$APP_OUT"
        --flash-ready-output "$FINAL_OUT"
        --metadata "$META_OUT"
    )
    if [ -n "$FACTORY_BIN_INPUT" ]; then
        FACTORY_ARGS+=(--record-input "$FACTORY_BIN_INPUT")
    else
        FACTORY_ARGS+=(--serial "$SERIAL" --hw-revision "$HW_REVISION")
        if [ -n "$STATIC_ADDRESS" ]; then
            FACTORY_ARGS+=(--static-address "$STATIC_ADDRESS")
        elif [ "$GENERATE_STATIC_ADDRESS" -ne 0 ]; then
            FACTORY_ARGS+=(--generate-static-address)
        fi
    fi
    python3 "$ROOT/tools/make_factory_identity.py" "${FACTORY_ARGS[@]}"
    [ -s "$FINAL_OUT" ] || { echo "error: flash-ready HEX was not produced" >&2; exit 1; }
    echo "hex: $FINAL_OUT"
    echo "identity: embedded factory sector"
    echo "factory-bin: $FACTORY_OUT"
    echo "identity-meta: $META_OUT"
else
    echo "hex: $FINAL_OUT"
    echo "identity: application-only (requires an existing factory sector)"
    echo "NOTE: for the first flash use --serial N; otherwise BLE advertising is intentionally disabled."
fi
