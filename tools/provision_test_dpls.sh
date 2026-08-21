#!/bin/bash
# Первичное provisioning Test-DPLS: application HEX + factory identity → один HEX.
#
#   tools/provision_test_dpls.sh <application.hex> <serial>
#
# Runtime-прошивка не содержит legacy/migration logic. Этот скрипт создаёт или
# переиспользует factory identity и прошивает application + factory sector одним
# заходом в ROM bootloader.
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
FACTORY_BIN="${DPLS_FACTORY_BIN:-$APP_DIR/$STEM.serial-$SERIAL.factory.bin}"
FACTORY_META="$APP_DIR/$STEM.serial-$SERIAL.identity.json"
FLASH_READY="$APP_DIR/$STEM.serial-$SERIAL.flash-ready.hex"

umask 077
ARGS=(
  --binary-output "$FACTORY_BIN"
  --merge-app-hex "$APP_HEX"
  --flash-ready-output "$FLASH_READY"
  --metadata "$FACTORY_META"
)

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
  flash-ready: $FLASH_READY
  factory BIN: $FACTORY_BIN

Factory BIN содержит постоянные IRK/CSRK. Сохраните его: при повторной сборке
используйте тот же BIN, иначе Bluetooth identity изменится и старые bond'ы сломаются.

Будет ОДИН вход в ROM bootloader.
Зажмите KEY1 и отпустите, когда programmer напишет «Turn on the power».
EOF

"$ROOT/tools/flash_firmware.sh" "$FLASH_READY"

cat <<EOF

Provisioning завершён.
1. Перезапустите плату обычным питанием, KEY1 не удерживайте.
2. В приложении ищите: Test-DPLS-$SUFFIX
3. Не удаляйте: $FACTORY_BIN
EOF
