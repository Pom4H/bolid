#!/bin/bash
# Прошивка PB-03F через rdwr_phy62x2.py из third_party/phy62x2.
#
#   tools/flash_firmware.sh <файл.hex> [--erase]
#
# Обычная прошивка обновляет только сектора из HEX.
# --erase очищает только DPLS/SNV work area 0x3C000..0x3EFFF перед прошивкой.
# ВАЖНО: vendor chip erase (-a/ea) здесь намеренно не используется: PHY6252
# хранит заводские ChipID/MAC words около 0x11000800/0x11000900, и полный erase
# уничтожает их вместе с factory sector.
#
# RTS/DTR у адаптера кита не разведены, автосброс невозможен:
#   1) зажмите KEY1 (на шелке RST/PROG — кнопка рубит питание чипа);
#   2) запустите скрипт;
#   3) отпустите кнопку, когда появится строка «Turn on the power».
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEX="${1:?usage: flash_firmware.sh <file.hex> [--erase]}"
MODE="${2:-}"
PORT="${PORT:-$(ls /dev/cu.wchusbserial* 2>/dev/null | head -1)}"
[ -n "$PORT" ] || { echo "USB-UART адаптер (CH340) не найден" >&2; exit 1; }
[ -z "$MODE" ] || [ "$MODE" = "--erase" ] || {
  echo "Неизвестный параметр: $MODE" >&2
  echo "usage: flash_firmware.sh <file.hex> [--erase]" >&2
  exit 2
}

FLASHER="$ROOT/third_party/phy62x2/Utils/rdwr_phy62x2.py"
export PYTHONPATH="$ROOT/.python-deps"

echo "Порт: $PORT — зажмите KEY1 и отпустите на строке «Turn on the power»"

if [ "$MODE" = "--erase" ]; then
  echo "Безопасная очистка: SNV 0x3C000..0x3EFFF; ChipID/MAC и factory sector сохраняются"
  python3 "$FLASHER" -p "$PORT" er 0x3C000 0x3000
  # Первый процесс оставляет ROM programmer активным на 115200. -n продолжает
  # ту же сессию без второго power-cycle и после записи делает reset.
  exec python3 "$FLASHER" -p "$PORT" -n -r wh "$HEX"
fi

exec python3 "$FLASHER" -p "$PORT" -r wh "$HEX"
