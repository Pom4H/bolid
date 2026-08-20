#!/bin/bash
# Первичное provisioning Test-DPLS: application HEX + обязательная factory identity.
#
#   tools/provision_test_dpls.sh <firmware.hex> <serial>
#
# Скрипт предназначен для НОВОЙ/СТЁРТОЙ платы. Factory identity содержит
# постоянные IRK/CSRK; не запускайте provisioning повторно на серийном приборе
# без осознанной замены его factory identity.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEX="${1:?usage: provision_test_dpls.sh <firmware.hex> <serial>}"
SERIAL="${2:?usage: provision_test_dpls.sh <firmware.hex> <serial>}"

[ -f "$HEX" ] || { echo "Firmware HEX не найден: $HEX" >&2; exit 1; }
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

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/test-dpls-provision.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM
umask 077
FACTORY_BIN="$TMP_DIR/factory.bin"
FACTORY_META="$TMP_DIR/factory.json"

python3 "$ROOT/tools/make_factory_identity.py" \
  --serial "$SERIAL" \
  --hw-revision 2 \
  --binary-output "$FACTORY_BIN" \
  --metadata "$FACTORY_META"

SUFFIX=$(printf '%04X' $((SERIAL & 0xffff)))
cat <<EOF

Первичное provisioning Test-DPLS
  порт:       $PORT
  firmware:   $HEX
  serial:     $SERIAL
  BLE name:   Test-DPLS-$SUFFIX

Будет ДВА входа в ROM bootloader: сначала application, затем factory identity.
На каждом шаге зажмите KEY1 и отпустите, когда programmer напишет «Turn on the power».
EOF

printf '\n[1/2] Application firmware\n'
"$ROOT/tools/flash_firmware.sh" "$HEX"

printf '\n[2/2] Factory identity\n'
"$ROOT/tools/flash_factory_identity.sh" "$FACTORY_BIN"

cat <<EOF

Provisioning завершён.
1. Перезапустите плату обычным питанием, KEY1 не удерживайте.
2. В приложении ищите: Test-DPLS-$SUFFIX
3. Если устройство не появилось, НЕ делайте full erase: сначала проверьте UART/reset cause и factory identity.
EOF
