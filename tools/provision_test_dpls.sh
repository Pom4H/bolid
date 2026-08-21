#!/bin/bash
# Первичное provisioning Test-DPLS: application + отдельная factory identity.
#
#   tools/provision_test_dpls.sh <application.hex> <serial>
#
# Runtime не содержит migration/legacy logic. Application пишется `wh`, factory
# identity — отдельной raw-операцией `we 0x3F000`; flash_firmware.sh выполняет
# обе операции и не смешивает два формата данных.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_HEX="${1:?usage: provision_test_dpls.sh <application.hex> <serial>}"
SERIAL="${2:?usage: provision_test_dpls.sh <application.hex> <serial>}"

[ -f "$APP_HEX" ] || { echo "Application HEX не найден: $APP_HEX" >&2; exit 1; }
case "$SERIAL" in
  ''|*[!0-9]*) echo "serial должен быть целым числом 1..4294967294" >&2; exit 2 ;;
esac
if [ "$SERIAL" -lt 1 ] || [ "$SERIAL" -gt 4294967294 ]; then
  echo "serial должен быть в диапазоне 1..4294967294" >&2
  exit 2
fi

PORT="${PORT:-$(ls /dev/cu.wchusbserial* 2>/dev/null | head -1)}"
[ -n "$PORT" ] || { echo "USB-UART адаптер (CH340) не найден" >&2; exit 1; }
export PORT

APP_DIR="$(cd "$(dirname "$APP_HEX")" && pwd)"
APP_NAME="$(basename "$APP_HEX")"
STEM="${APP_NAME%.hex}"
DEFAULT_FACTORY_BIN="$APP_DIR/$STEM.factory.bin"
FACTORY_BIN="${DPLS_FACTORY_BIN:-$DEFAULT_FACTORY_BIN}"
FACTORY_META="$APP_DIR/$STEM.identity.json"

umask 077
ARGS=(--binary-output "$FACTORY_BIN" --metadata "$FACTORY_META")

if [ -f "$FACTORY_BIN" ]; then
  EXISTING_SERIAL=$(python3 - "$FACTORY_BIN" <<'PY'
import struct, sys
raw = open(sys.argv[1], 'rb').read()
if len(raw) != 64:
    raise SystemExit("existing factory BIN has wrong size")
print(struct.unpack_from('<I', raw, 8)[0])
PY
)
  if [ "$EXISTING_SERIAL" != "$SERIAL" ]; then
    echo "ОТКАЗ: $FACTORY_BIN принадлежит serial=$EXISTING_SERIAL, а запрошен $SERIAL" >&2
    exit 2
  fi
  ARGS+=(--record-input "$FACTORY_BIN")
  IDENTITY_ACTION="переиспользуется существующая identity"
else
  ARGS+=(--serial "$SERIAL" --hw-revision 2)
  IDENTITY_ACTION="создаётся новая identity"
fi

python3 "$ROOT/tools/make_factory_identity.py" "${ARGS[@]}"

SUFFIX=$(printf '%04X' $((SERIAL & 0xffff)))
cat <<EOF

Provisioning Test-DPLS
  порт:        $PORT
  application: $APP_HEX
  serial:      $SERIAL
  BLE name:    Test-DPLS-$SUFFIX
  identity:    $IDENTITY_ACTION
  factory BIN: $FACTORY_BIN

Application и factory sector НЕ объединяются.
Прошивка состоит из двух programmer operations:
  1) application: wh
  2) factory:     we 0x3F000
Для каждой операции нужен вход в ROM bootloader через KEY1.
EOF

DPLS_FACTORY_BIN="$FACTORY_BIN" "$ROOT/tools/flash_firmware.sh" "$APP_HEX"

cat <<EOF

Provisioning завершён.
1. Полностью перезапустите питание платы, KEY1 не удерживайте.
2. В приложении ищите: Test-DPLS-$SUFFIX
3. Сохраните $FACTORY_BIN — там постоянные IRK/CSRK этой платы.
EOF
