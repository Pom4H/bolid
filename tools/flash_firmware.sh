#!/bin/bash
# Прошивка PB-03F через pvvx rdwr_phy62x2.py.
#
#   tools/flash_firmware.sh <файл.hex> [--erase]
#
# --erase стирает весь чип, ВКЛЮЧАЯ SNV (журнал, настройки, бонды).
# Без него обновляются только секторы приложения, данные сохраняются.
#
# RTS/DTR у адаптера кита не разведены, автосброс невозможен:
#   1) зажмите KEY1 (на шелке RST/PROG — кнопка рубит питание чипа);
#   2) запустите скрипт;
#   3) отпустите кнопку, когда появится строка «Turn on the power».
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEX="${1:?usage: flash_firmware.sh <file.hex> [--erase]}"
PORT="${PORT:-$(ls /dev/cu.wchusbserial* 2>/dev/null | head -1)}"
[ -n "$PORT" ] || { echo "USB-UART адаптер (CH340) не найден" >&2; exit 1; }

ARGS=(-p "$PORT" -r wh "$HEX")
[ "${2:-}" = "--erase" ] && ARGS=(-p "$PORT" -a -r wh "$HEX")

echo "Порт: $PORT — зажмите KEY1 и отпустите на строке «Turn on the power»"
PYTHONPATH="$ROOT/.python-deps" exec python3 "$ROOT/pvvx-PHY62x2/Utils/rdwr_phy62x2.py" "${ARGS[@]}"
