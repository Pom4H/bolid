#!/usr/bin/env bash
# Unattended PB-03F flashing for agents/CI benches.
#
#   tools/flash_firmware_agent.sh <application.hex> [--erase]
#
# Requires an adapter/fixture where the PHY62x2 ROM-entry control lines are
# connected. rdwr_phy62x2.py drives RTS/DTR, opens UART at 9600 and repeatedly
# sends UXTDWU until the ROM replies cmd>>:. No KEY1 or stdin interaction.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEX="${1:?usage: flash_firmware_agent.sh <application.hex> [--erase]}"
PORT="${PORT:-$(ls /dev/cu.wchusbserial* /dev/ttyUSB* 2>/dev/null | head -1)}"
ERASE=0

[ -f "$HEX" ] || { echo "Application HEX не найден: $HEX" >&2; exit 1; }
[ -n "$PORT" ] || { echo "USB-UART адаптер не найден; задайте PORT=/dev/..." >&2; exit 1; }
if [ "${2:-}" = "--erase" ]; then ERASE=1; fi
if [ "$#" -gt 2 ] || { [ "$#" -eq 2 ] && [ "${2:-}" != "--erase" ]; }; then
  echo "usage: flash_firmware_agent.sh <application.hex> [--erase]" >&2
  exit 2
fi

PROGRAMMER="$ROOT/third_party/phy62x2/Utils/rdwr_phy62x2.py"
ARGS=(-p "$PORT" -r wh "$HEX")
if [ "$ERASE" -eq 1 ]; then
  ARGS=(-p "$PORT" -a -r wh "$HEX")
fi

echo "Agent flash: $HEX -> $PORT (ROM UART 9600 UXTDWU, automatic RTS/DTR entry)"
PYTHONPATH="$ROOT/.python-deps" exec python3 "$PROGRAMMER" "${ARGS[@]}"
