#!/usr/bin/env bash
# Build an AC6 diagnostic PHY6252 image with UART logs, power diagnostics and
# application-assisted ROM entry. This image is intentionally not a release or
# absolute-current measurement artifact: UART logging changes CPU/current load.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/tmp/test-dpls-debug-rom.hex}"
CONNECTED_SLEEP="${DPLS_CONNECTED_SLEEP:-1}"

case "$OUT" in
    /*) ;;
    *) OUT="$ROOT/$OUT" ;;
esac

case "$CONNECTED_SLEEP" in
    0|1) ;;
    *) echo "error: DPLS_CONNECTED_SLEEP must be 0 or 1" >&2; exit 2 ;;
esac

echo "==> PHY6252 diagnostic build"
echo "    DEBUG_INFO=1"
echo "    DPLS_DEBUG_UART_ROM=1"
echo "    DPLS_POWER_DIAG_LOG=1"
echo "    DPLS_CONNECTED_SLEEP=$CONNECTED_SLEEP"

DPLS_BUILD_PROFILE=debug-rom \
DPLS_CONNECTED_SLEEP="$CONNECTED_SLEEP" \
    bash "$ROOT/tools/build_firmware.sh" "$OUT"

python3 - "$OUT" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
needles = {
    "ROM-entry token": bytes((0x00, 0xD5, *b"DPLS-ROM", 0xA5, 0x5A, 0xC3, 0x3C, 0x7E, 0x81)),
    "power diagnostics": b"DPLS PWR t=",
    "safe ROM handoff": b"DPLS ROM READY",
}
memory: dict[int, int] = {}
upper = 0
for raw in path.read_text(encoding="ascii").splitlines():
    record = bytes.fromhex(raw[1:])
    size = record[0]
    address = int.from_bytes(record[1:3], "big")
    kind = record[3]
    data = record[4 : 4 + size]
    if kind == 0x04:
        upper = int.from_bytes(data, "big") << 16
    elif kind == 0x00:
        for offset, value in enumerate(data):
            memory[upper + address + offset] = value

for label, needle in needles.items():
    if not any(
        all(memory.get(start + offset) == value for offset, value in enumerate(needle))
        for start in memory
    ):
        raise SystemExit(f"error: diagnostic HEX does not contain {label}")
    print(f"diagnostic {label}: PASS")
PY

printf '%s\n' \
    "image=$OUT" \
    "toolchain=Arm-Compiler-6.24.0" \
    "DEBUG_INFO=1" \
    "DPLS_DEBUG_UART_ROM=1" \
    "DPLS_POWER_DIAG_LOG=1" \
    "DPLS_CONNECTED_SLEEP=$CONNECTED_SLEEP" \
    "commit=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || printf unknown)" \
    > "$OUT.build.txt"

echo "diagnostic hex: $OUT"
echo "metadata:       $OUT.build.txt"
