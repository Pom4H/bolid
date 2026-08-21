#!/bin/bash
# Прошивка PB-03F через rdwr_phy62x2.py из third_party/phy62x2.
#
#   tools/flash_firmware.sh <application.hex> [--erase]
#
# Прошивается ровно один application HEX штатной операцией `wh`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEX="${1:?usage: flash_firmware.sh <application.hex> [--erase]}"
PORT="${PORT:-$(ls /dev/cu.wchusbserial* 2>/dev/null | head -1)}"
ERASE=0

[ -f "$HEX" ] || { echo "Application HEX не найден: $HEX" >&2; exit 1; }
[ -n "$PORT" ] || { echo "USB-UART адаптер (CH340) не найден" >&2; exit 1; }
if [ "${2:-}" = "--erase" ]; then ERASE=1; fi
if [ "$#" -gt 2 ] || { [ "$#" -eq 2 ] && [ "${2:-}" != "--erase" ]; }; then
  echo "usage: flash_firmware.sh <application.hex> [--erase]" >&2
  exit 2
fi

PROGRAMMER="$ROOT/third_party/phy62x2/Utils/rdwr_phy62x2.py"
run_programmer() {
  PYTHONPATH="$ROOT/.python-deps" python3 "$PROGRAMMER" "$@"
}

wait_for_bootloader_entry() {
  if [ "${DPLS_NO_FLASH_PROMPT:-0}" = "1" ]; then
    echo "Ожидается, что плата уже удерживается в ROM bootloader."
    return
  fi
  if [ ! -t 0 ]; then
    echo "ОТКАЗ: нужен интерактивный вход в ROM bootloader через KEY1." >&2
    echo "Для автоматизированного стенда задайте DPLS_NO_FLASH_PROMPT=1." >&2
    exit 2
  fi
  echo
  echo "Прошивка application"
  echo "1. Зажмите KEY1 и НЕ отпускайте."
  echo "2. Нажмите Enter, продолжая удерживать KEY1."
  echo "3. Отпустите KEY1 только когда programmer напишет: Turn on the power..."
  read -r
}

cat <<EOF
Порт: $PORT
Application: $HEX
EOF

ARGS=(-p "$PORT" -r wh "$HEX")
if [ "$ERASE" -eq 1 ]; then
  echo "ВНИМАНИЕ: --erase стирает chip, включая SNV/bonds." >&2
  ARGS=(-p "$PORT" -a -r wh "$HEX")
fi

wait_for_bootloader_entry
run_programmer "${ARGS[@]}"

cat <<'EOF'

Прошивка завершена. Полностью перезапустите питание платы, KEY1 не удерживайте.
EOF
