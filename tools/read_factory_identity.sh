#!/bin/bash
# Безопасно читает factory identity PHY6252 и проверяет формат/CRC.
# Ничего во flash не пишет и ничего не стирает.
#
#   tools/read_factory_identity.sh
#   PORT=/dev/cu.wchusbserial110 tools/read_factory_identity.sh
#
# Как и при обычной прошивке PB-03F-Kit:
#   1) зажмите KEY1;
#   2) запустите скрипт;
#   3) отпустите KEY1 на строке «Turn on the power».
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-$(ls /dev/cu.wchusbserial* 2>/dev/null | head -1)}"
[ -n "$PORT" ] || { echo "USB-UART адаптер (CH340) не найден" >&2; exit 1; }

OUT="$(mktemp -t dpls-factory-identity.XXXXXX.bin)"
trap 'rm -f "$OUT"' EXIT

if [ ! -d "$ROOT/.python-deps/serial" ] && ! python3 -c 'import serial' >/dev/null 2>&1; then
  mkdir -p "$ROOT/.python-deps"
  python3 -m pip install --quiet --target "$ROOT/.python-deps" pyserial
fi

echo "Порт: $PORT — зажмите KEY1 и отпустите на строке «Turn on the power»"
PYTHONPATH="$ROOT/.python-deps" python3 \
  "$ROOT/third_party/phy62x2/Utils/rdwr_phy62x2.py" \
  -p "$PORT" -r rc 0x1103F000 64 "$OUT"

python3 - "$OUT" <<'PY'
from pathlib import Path
import hashlib
import struct
import sys

path = Path(sys.argv[1])
raw = path.read_bytes()
if len(raw) != 64:
    raise SystemExit(f"FACTORY: READ ERROR — ожидалось 64 байта, получено {len(raw)}")


def crc16_ccitt_false(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc

if raw == b"\xff" * 64:
    print("FACTORY: ERASED (64 x FF)")
    raise SystemExit(2)
if raw == b"\x00" * 64:
    print("FACTORY: BLANK (64 x 00)")
    raise SystemExit(2)

magic, version, size, serial, hw_revision, flags = struct.unpack_from("<IHHIHH", raw, 0)
stored_crc = struct.unpack_from("<H", raw, 62)[0]
actual_crc = crc16_ccitt_false(raw[:62])

print(f"FACTORY: address=0x1103F000 sha256={hashlib.sha256(raw).hexdigest()}")
print(f"FACTORY: magic={raw[:4]!r} version={version} size={size} serial={serial} hw={hw_revision} flags=0x{flags:04X}")
print(f"FACTORY: crc stored=0x{stored_crc:04X} actual=0x{actual_crc:04X}")

errors = []
if magic != 0x31444944:
    errors.append("magic != DID1")
if version != 1:
    errors.append("version != 1")
if size != 64:
    errors.append("size != 64")
if serial in (0, 0xFFFFFFFF):
    errors.append("invalid serial")
if stored_crc != actual_crc:
    errors.append("CRC mismatch")

if errors:
    print("FACTORY: INVALID — " + ", ".join(errors))
    raise SystemExit(2)

addr_type = raw[22]
if addr_type == 0:
    addr_desc = "PHY6252 factory public MAC"
elif addr_type == 1:
    addr_desc = "static random MAC " + raw[16:22].hex(":").upper()
else:
    addr_desc = f"unknown type {addr_type}"
print(f"FACTORY: VALID — {addr_desc}")
PY
