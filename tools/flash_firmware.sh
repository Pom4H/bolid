#!/bin/bash
# Прошивка PB-03F через rdwr_phy62x2.py из third_party/phy62x2.
#
#   tools/flash_firmware.sh <файл.hex> [--erase]
#
# --erase стирает весь чип, ВКЛЮЧАЯ SNV (журнал, настройки, бонды).
# Без него обновляются только секторы приложения, данные сохраняются.
#
# Новая прошивка сама передаёт управление ROM-загрузчику по UART. Для старой
# версии остаётся резервный ручной способ: нажать RST во время синхронизации.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEX="${1:?usage: flash_firmware.sh <file.hex> [--erase]}"
PORT="${PORT:-$(ls /dev/cu.wchusbserial* 2>/dev/null | head -1)}"
[ -n "$PORT" ] || { echo "USB-UART адаптер (CH340) не найден" >&2; exit 1; }

if PYTHONPATH="$ROOT/.python-deps" python3 "$ROOT/tools/enter_phy6252_bootloader.py" --port "$PORT"; then
    echo "Порт: $PORT — ROM-загрузчик запрошен программно"
    ENTERED_ROM=1
else
    echo "Порт: $PORT — старая прошивка не подтвердила автопереход; при необходимости нажмите RST" >&2
    ENTERED_ROM=0
fi

if [ "$ENTERED_ROM" = 1 ]; then
    ARGS=(-p "$PORT" -n -r wh "$HEX")
    [ "${2:-}" = "--erase" ] && ARGS=(-p "$PORT" -n -a -r wh "$HEX")
else
    ARGS=(-p "$PORT" -r wh "$HEX")
    [ "${2:-}" = "--erase" ] && ARGS=(-p "$PORT" -a -r wh "$HEX")
fi
PYTHONPATH="$ROOT/.python-deps" exec python3 "$ROOT/third_party/phy62x2/Utils/rdwr_phy62x2.py" "${ARGS[@]}"
