#!/bin/bash
# Запись 64-байтной factory identity в отдельный сектор PHY6252.
#
#   tools/flash_factory_identity.sh <factory.bin>
#
# ВАЖНО: используем операцию `we` по flash offset 0x3F000, а не `wh`.
# `wh` формирует application segment table в 0x2000 и поэтому не подходит
# для независимого provisioning factory data.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="${1:?usage: flash_factory_identity.sh <factory.bin>}"
PORT="${PORT:-$(ls /dev/cu.wchusbserial* 2>/dev/null | head -1)}"
FACTORY_OFFSET=0x3F000
FACTORY_SIZE=64

[ -f "$BIN" ] || { echo "Factory BIN не найден: $BIN" >&2; exit 1; }
[ -n "$PORT" ] || { echo "USB-UART адаптер (CH340) не найден" >&2; exit 1; }

SIZE=$(wc -c < "$BIN" | tr -d '[:space:]')
if [ "$SIZE" -ne "$FACTORY_SIZE" ]; then
  echo "ОТКАЗ: factory BIN должен быть ровно $FACTORY_SIZE байта, получено $SIZE" >&2
  exit 2
fi

cat <<EOF
Порт: $PORT
Factory sector offset: $FACTORY_OFFSET
Файл: $BIN ($SIZE bytes)
Зажмите KEY1 и отпустите на строке «Turn on the power».
EOF

# `we` автоматически стирает только 4 KiB sector, затронутый 64-байтной записью,
# затем пишет BIN по указанному flash offset. Application header и SNV не трогаются.
PYTHONPATH="$ROOT/.python-deps" exec python3 \
  "$ROOT/third_party/phy62x2/Utils/rdwr_phy62x2.py" \
  -p "$PORT" -r we "$FACTORY_OFFSET" "$BIN"
