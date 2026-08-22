#!/bin/bash
# Прошивка PB-03F через rdwr_phy62x2.py из third_party/phy62x2.
#
#   tools/flash_firmware.sh <файл.hex> [--erase]
#
# Обычная прошивка обновляет только сектора из HEX и сохраняет SNV и factory
# identity. --erase стирает ВЕСЬ чип, включая серийный номер/ключи в 0x1103F000,
# поэтому для него требуется явное DPLS_ALLOW_FACTORY_ERASE=1.
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
if [ "${2:-}" = "--erase" ]; then
  if [ "${DPLS_ALLOW_FACTORY_ERASE:-0}" != "1" ]; then
    cat >&2 <<'EOF'
ОТКАЗ: --erase стирает factory identity (серийный номер, BLE identity keys).
Для непровиженного прототипа можно повторить с DPLS_ALLOW_FACTORY_ERASE=1.
На серийном приборе после полного стирания factory identity нужно восстановить
из производственной записи до запуска изделия.
EOF
    exit 2
  fi
  ARGS=(-p "$PORT" -a -r wh "$HEX")
fi

echo "Порт: $PORT — зажмите KEY1 и отпустите на строке «Turn on the power»"
PYTHONPATH="$ROOT/.python-deps" exec python3 "$ROOT/third_party/phy62x2/Utils/rdwr_phy62x2.py" "${ARGS[@]}"
