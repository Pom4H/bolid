#!/bin/bash
# Прошивка PB-03F через rdwr_phy62x2.py из third_party/phy62x2.
#
#   tools/flash_firmware.sh <application.hex> [--erase]
#
# Application всегда пишется штатной операцией wh. Если рядом лежит
# <application>.factory.bin (или задан DPLS_FACTORY_BIN), скрипт после этого
# отдельно пишет factory identity raw-операцией we 0x3F000.
#
# НИКОГДА не объединяйте factory sector с application HEX: wh формирует
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

wait_for_bootloader_entry() {
  local stage="$1"
  if [ "${DPLS_NO_FLASH_PROMPT:-0}" = "1" ]; then
    echo "$stage: ожидается, что плата уже удерживается в ROM bootloader."
    return
  fi
  if [ ! -t 0 ]; then
    echo "ОТКАЗ: для $stage нужен интерактивный вход в ROM bootloader через KEY1." >&2
    echo "Для автоматизированного стенда задайте DPLS_NO_FLASH_PROMPT=1 и обеспечьте reset/power externally." >&2
    exit 2
  fi
  echo
  echo "$stage"
  echo "1. Зажмите KEY1 и НЕ отпускайте."
  echo "2. Нажмите Enter, продолжая удерживать KEY1."
  echo "3. Отпустите KEY1 только когда programmer напишет: Turn on the power..."
  read -r
}

cat <<EOF
Порт: $PORT
Application: $HEX
Factory: $([ "$HAS_FACTORY" -eq 1 ] && printf '%s' "$FACTORY_BIN" || printf 'не задана — сохранится существующая')
EOF

APP_ARGS=(-p "$PORT" -r wh "$HEX")
if [ "$ERASE" -eq 1 ]; then APP_ARGS=(-p "$PORT" -a -r wh "$HEX"); fi

TOTAL=1
if [ "$HAS_FACTORY" -eq 1 ]; then TOTAL=2; fi
printf '\n[1/%s] Application (wh)\n' "$TOTAL"
wait_for_bootloader_entry "Прошивка application"
run_programmer "${APP_ARGS[@]}"

if [ "$HAS_FACTORY" -eq 1 ]; then
  printf '\n[2/2] Factory identity (we 0x3F000)\n'
  wait_for_bootloader_entry "Прошивка factory identity"
  run_programmer -p "$PORT" -r we "$FACTORY_OFFSET" "$FACTORY_BIN"
fi

cat <<'EOF'

Прошивка завершена. Полностью перезапустите питание платы, KEY1 не удерживайте.
EOF
