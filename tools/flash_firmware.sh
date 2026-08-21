#!/usr/bin/env bash
# Прошивка PB-03F через PHY62x2 ROM UART monitor.
#
#   tools/flash_firmware.sh <application.hex> [--erase]
#
# Скрипт ничего не читает из stdin. Programmer сам посылает ROM handshake
# UXTDWU на 9600 бод и ждёт входа платы в bootloader.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEX="${1:?usage: flash_firmware.sh <application.hex> [--erase]}"
PORT="${PORT:-$(ls /dev/cu.wchusbserial* /dev/ttyUSB* 2>/dev/null | head -1)}"
ERASE=0

[ -f "$HEX" ] || { echo "Application HEX не найден: $HEX" >&2; exit 1; }
[ -n "$PORT" ] || { echo "USB-UART адаптер не найден; задайте PORT=/dev/..." >&2; exit 1; }
if [ "${2:-}" = "--erase" ]; then ERASE=1; fi
if [ "$#" -gt 2 ] || { [ "$#" -eq 2 ] && [ "${2:-}" != "--erase" ]; }; then
  echo "usage: flash_firmware.sh <application.hex> [--erase]" >&2
  exit 2
fi

PROGRAMMER="$ROOT/third_party/phy62x2/Utils/rdwr_phy62x2.py"
ARGS=(-p "$PORT" -r wh "$HEX")
if [ "$ERASE" -eq 1 ]; then
  echo "ВНИМАНИЕ: --erase стирает chip, включая SNV/bonds." >&2
  ARGS=(-p "$PORT" -a -r wh "$HEX")
fi

cat <<EOF
Порт: $PORT
Application: $HEX
Если автоматический reset/test-mode не подключён, удерживайте KEY1 и сделайте reset/перезапуск питания.
Programmer уже посылает UXTDWU на 9600 бод — Enter нажимать не нужно.
EOF

PYTHONPATH="$ROOT/.python-deps" exec python3 "$PROGRAMMER" "${ARGS[@]}"
