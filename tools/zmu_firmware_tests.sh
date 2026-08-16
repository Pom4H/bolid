#!/usr/bin/env bash
# Build and run portable firmware host tests on zmu Cortex-M0 (no hardware).
# Uses -O0: jjkt/zmu currently mishandles some Thumb LDM/STM copies under -O1+.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ZMU_BIN="${1:-${ZMU_BIN:-}}"
OUT="$ROOT/tmp/zmu-firmware-tests"
STARTUP="$ROOT/firmware/zmu/startup_ARMCM0.S"

if [[ -z "$ZMU_BIN" || ! -x "$ZMU_BIN" ]]; then
    echo "usage: $0 /path/to/zmu-cortex-m0" >&2
    exit 2
fi
if [[ ! -f "$STARTUP" ]]; then
    echo "missing $STARTUP" >&2
    exit 3
fi

mkdir -p "$OUT/include"
cat > "$OUT/include/assert.h" <<'EOF'
#pragma once
#include <stdio.h>
#include <stdlib.h>
#undef assert
#define assert(expr) do { \
    if (!(expr)) { \
        printf("ASSERT_FAIL:%s:%d:%s\n", __FILE__, __LINE__, #expr); \
        fflush(stdout); \
        exit(1); \
    } \
} while (0)
EOF

cat > "$OUT/zmu_boot.c" <<'EOF'
#include <stdlib.h>
extern void initialise_monitor_handles(void);
extern int main(void);
void SystemInit(void) {}
void _start(void) {
    initialise_monitor_handles();
    exit(main());
}
__attribute__((used)) void _fini(void) {}
EOF

TESTS=(
    test_server_v2
    test_identify_contract
    test_led
    test_calib
    test_protocol
    test_safety
    test_adc_irq_model
)

pass=0
fail=0
for name in "${TESTS[@]}"; do
    src="$ROOT/firmware/tests/${name}.c"
    elf="$OUT/${name}.elf"
    log="$OUT/${name}.log"
    echo "=== zmu $name ==="
    extras=()
    if [[ "$name" != "test_adc_irq_model" ]]; then
        extras=(
            "$ROOT/firmware/src/dpls_protocol.c"
            "$ROOT/firmware/src/dpls_server.c"
            "$ROOT/firmware/src/dpls_safety.c"
            "$ROOT/firmware/src/dpls_led.c"
            "$ROOT/firmware/src/dpls_calib.c"
        )
    fi
    arm-none-eabi-gcc \
        -O0 -g3 -mthumb -mcpu=cortex-m0 \
        --specs=rdimon.specs -nostartfiles \
        -D__STARTUP_CLEAR_BSS -D__STACK_SIZE=0x8000 -D__HEAP_SIZE=0x2000 \
        -ffunction-sections -fdata-sections \
        -Wall -Wextra -Werror \
        -I"$OUT/include" \
        -I"$ROOT/firmware/include" \
        -T"$ROOT/firmware/zmu/link.ld" \
        "$src" \
        "${extras[@]}" \
        "$OUT/zmu_boot.c" \
        "$STARTUP" \
        -Wl,--gc-sections \
        -lc -lrdimon \
        -o "$elf"
    if "$ZMU_BIN" run "$elf" | tee "$log"; then
        echo "PASS $name"
        pass=$((pass + 1))
    else
        echo "FAIL $name" >&2
        fail=$((fail + 1))
    fi
done

echo "zmu firmware tests: $pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
