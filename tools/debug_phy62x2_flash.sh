#!/usr/bin/env bash
set -euo pipefail

# Отладка ROM handshake PHY62x2.
# Не изменяет vendor utility. Показывает, проходит ли вход в ROM.

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
HEX="${1:-${ROOT_DIR}/tmp/TestDPLS-1.5.0.hex}"
PORT="${2:-}"

if [[ -z "$PORT" ]]; then
  for candidate in /dev/cu.wchusbserial* /dev/cu.usbserial* /dev/cu.usbmodem* /dev/ttyUSB* /dev/ttyACM*; do
    if [[ -e "$candidate" ]]; then
      PORT="$candidate"
      break
    fi
  done
fi

if [[ -z "$PORT" ]]; then
  echo "error: UART port not found"
  exit 2
fi

if [[ ! -f "$HEX" ]]; then
  echo "error: hex not found: $HEX"
  exit 2
fi

echo "PHY62x2 debug flash"
echo "port: $PORT"
echo "hex:  $HEX"
echo
echo "ROM handshake debug:"
echo "  baud: 9600"
echo "  magic: UXTDWU"
echo

PYTHONUNBUFFERED=1 python3 "$ROOT_DIR/third_party/phy62x2/Utils/rdwr_phy62x2.py" \
  -p "$PORT" \
  --debug \
  wh "$HEX"
