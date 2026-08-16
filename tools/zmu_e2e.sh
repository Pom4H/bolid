#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ZMU_BIN="${1:-${ZMU_BIN:-}}"
OUT="$ROOT/tmp/zmu-e2e"

if [[ -z "$ZMU_BIN" || ! -x "$ZMU_BIN" ]]; then
    echo "usage: $0 /path/to/zmu-cortex-m0" >&2
    echo "  or:  ZMU_BIN=/path/to/zmu-cortex-m0 $0" >&2
    exit 2
fi

mkdir -p "$OUT"

(
    cd "$ROOT/mobile"
    DPLS_ZMU_GENERATE="$OUT/zmu_vectors.h" \
        ./gradlew :interop:jvmTest --rerun-tasks \
        --tests ru.bolid.testdpls.interop.ZmuInteropTest.generateVectorsWhenRequested
)

STARTUP="$ROOT/firmware/zmu/startup_ARMCM0.S"
if [[ ! -f "$STARTUP" ]]; then
    for candidate in \
        /usr/share/gcc-arm-none-eabi/samples/startup/startup_ARMCM0.S \
        /usr/share/doc/gcc-arm-none-eabi/examples/startup/startup_ARMCM0.S \
        /usr/share/gcc-arm-embedded/samples/startup/startup_ARMCM0.S \
        "${GCC_HOME:-}/share/gcc-arm-none-eabi/samples/startup/startup_ARMCM0.S" \
        "${GCC_HOME:-}/share/gcc-arm-embedded/samples/startup/startup_ARMCM0.S"
    do
        if [[ -n "$candidate" && -f "$candidate" ]]; then
            STARTUP="$candidate"
            break
        fi
    done
fi

if [[ ! -f "$STARTUP" ]]; then
    echo "startup_ARMCM0.S not found (expected under firmware/zmu/ or gcc-arm-none-eabi samples)" >&2
    exit 3
fi

arm-none-eabi-gcc \
    -Os -g3 -mthumb -mcpu=cortex-m0 \
    --specs=rdimon.specs -nostartfiles \
    -D__STARTUP_CLEAR_BSS \
    -ffunction-sections -fdata-sections \
    -Wall -Wextra -Werror \
    -I"$ROOT/firmware/include" \
    -I"$OUT" \
    -T"$ROOT/firmware/zmu/link.ld" \
    "$ROOT/firmware/zmu/zmu_e2e.c" \
    "$ROOT/firmware/src/dpls_protocol.c" \
    "$ROOT/firmware/src/dpls_server.c" \
    "$ROOT/firmware/src/dpls_safety.c" \
    "$ROOT/firmware/src/dpls_led.c" \
    "$STARTUP" \
    -Wl,--gc-sections \
    -lc -lrdimon \
    -o "$OUT/test-dpls-zmu.elf"

"$ZMU_BIN" run "$OUT/test-dpls-zmu.elf" | tee "$OUT/zmu-output.txt"
grep -q '^ZMU_E2E_OK ' "$OUT/zmu-output.txt"

(
    cd "$ROOT/mobile"
    DPLS_ZMU_VERIFY="$OUT/zmu-output.txt" \
        ./gradlew :interop:jvmTest --rerun-tasks \
        --tests ru.bolid.testdpls.interop.ZmuInteropTest.verifyOutputWhenRequested
)

echo "zmu E2E passed"
