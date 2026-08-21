#!/bin/bash
# Прошивка PB-03F через rdwr_phy62x2.py из third_party/phy62x2.
#
#   tools/flash_firmware.sh <application.hex> [--erase]
#
# Application всегда пишется штатной операцией `wh`. Если рядом лежит
# <application>.factory.bin (или задан DPLS_FACTORY_BIN), скрипт после этого
# отдельно пишет factory identity raw-операцией `we 0x3F000`.
#
# НИКОГДА не объединяйте factory sector с application HEX: `wh` формирует
# application segment table и не является generic multi-region HEX writer.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEX="${1:?usage: flash_firmware.sh <application.hex> [--erase]}"
PORT="${PORT:-$(ls /dev/cu.wchusbserial* 2>/dev/null | head -1)}"
FACTORY_BIN="${DPLS_FACTORY_BIN:-${HEX%.hex}.factory.bin}"
FACTORY_OFFSET=0x3F000
FACTORY_SIZE=64
ERASE=0

[ -f "$HEX" ] || { echo "Application HEX не найден: $HEX" >&2; exit 1; }
[ -n "$PORT" ] || { echo "USB-UART адаптер (CH340) не найден" >&2; exit 1; }
if [ "${2:-}" = "--erase" ]; then ERASE=1; fi
if [ "$#" -gt 2 ] || { [ "$#" -eq 2 ] && [ "${2:-}" != "--erase" ]; }; then
  echo "usage: flash_firmware.sh <application.hex> [--erase]" >&2
  exit 2
fi

HAS_FACTORY=0
if [ -f "$FACTORY_BIN" ]; then
  SIZE=$(wc -c < "$FACTORY_BIN" | tr -d '[:space:]')
  if [ "$SIZE" -ne "$FACTORY_SIZE" ]; then
    echo "ОТКАЗ: factory BIN должен быть ровно $FACTORY_SIZE байта, получено $SIZE: $FACTORY_BIN" >&2
    exit 2
  fi
  HAS_FACTORY=1
fi

if [ "$ERASE" -eq 1 ]; then
  if [ "${DPLS_ALLOW_FACTORY_ERASE:-0}" != "1" ]; then
    cat >&2 <<'EOF'
ОТКАЗ: --erase стирает весь chip, включая SNV и factory identity.
Для осознанного полного стирания задайте DPLS_ALLOW_FACTORY_ERASE=1.
EOF
    exit 2
  fi
  if [ "$HAS_FACTORY" -ne 1 ]; then
    echo "ОТКАЗ: после --erase strict RC6 не запустит BLE без factory identity sidecar: $FACTORY_BIN" >&2
    exit 2
  fi
fi

PROGRAMMER="$ROOT/third_party/phy62x2/Utils/rdwr_phy62x2.py"
run_programmer() {
  PYTHONPATH="$ROOT/.python-deps" python3 "$PROGRAMMER" "$@"
}

cat <<EOF
Порт: $PORT
Application: $HEX
Factory: $([ "$HAS_FACTORY" -eq 1 ] && printf '%s' "$FACTORY_BIN" || printf 'не задана — сохранится существующая')
EOF

APP_ARGS=(-p "$PORT" -r wh "$HEX")
if [ "$ERASE" -eq 1 ]; then APP_ARGS=(-p "$PORT" -a -r wh "$HEX"); fi

printf '\n[1/%s] Application (`wh`)\n' "$([ "$HAS_FACTORY" -eq 1 ] && echo 2 || echo 1)"
echo "Зажмите KEY1 и отпустите на строке «Turn on the power»."
run_programmer "${APP_ARGS[@]}"

if [ "$HAS_FACTORY" -eq 1 ]; then
  cat <<EOF

[2/2] Factory identity (`we 0x3F000`)
Нужен второй вход в ROM bootloader: снова зажмите KEY1 и отпустите на строке «Turn on the power».
EOF
  run_programmer -p "$PORT" -r we "$FACTORY_OFFSET" "$FACTORY_BIN"
fi

cat <<'EOF'

Прошивка завершена. Полностью перезапустите питание платы, KEY1 не удерживайте.
EOF
