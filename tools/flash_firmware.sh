#!/usr/bin/env bash
# Прошивка PB-03F через PHY62x2 ROM UART monitor.
#
#   tools/flash_firmware.sh [application.hex] [--auto-rst] [--erase] [--port /dev/...]
#
# Без --auto-rst: KEY1/reset выполняются вручную, Enter не нужен.
# С --auto-rst: RTS -> RST_N, DTR -> TM. Эти линии должны быть физически подключены.
#
# ВАЖНО: после записи приложение не сбрасывается сразу. Сначала ROM monitor
# читает первые 16 байт XIP обратно и сравнивает их с HEX. Этот readback служит
# одновременно проверкой записи и post-flash barrier. Только после него
# отправляется reset. На реальной PB-03F именно read -> reset устранил состояние,
# когда корректно записанная прошивка не стартовала после обычного `wh -r`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEX="$ROOT/tmp/test-dpls.hex"
HEX_SET=0
AUTO_RST=0
ERASE=0
PORT="${PORT:-}"
VERIFY_ADDR=0x11020000
VERIFY_SIZE=16

usage() {
    local status="${1:-2}"
    cat >&2 <<'EOF'
usage: tools/flash_firmware.sh [application.hex] [--auto-rst] [--erase] [--port /dev/...]

Defaults:
  application.hex: tmp/test-dpls.hex
  port:            first detected USB-UART

ROM entry:
  default      hold KEY1 and reset/power-cycle the board; no Enter
  --auto-rst   requires physical RTS -> RST_N and DTR -> TM wiring

Other:
  --erase      full chip erase; wipes SNV, bonds and factory identity
EOF
    exit "$status"
}

detect_port() {
    local candidate
    for candidate in \
        /dev/cu.wchusbserial* \
        /dev/cu.usbserial* \
        /dev/cu.usbmodem* \
        /dev/ttyUSB* \
        /dev/ttyACM*; do
        [ -e "$candidate" ] || continue
        printf '%s\n' "$candidate"
        return 0
    done
    return 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help) usage 0 ;;
        --auto-rst) AUTO_RST=1; shift ;;
        --erase) ERASE=1; shift ;;
        --port)
            [ "$#" -ge 2 ] || usage
            PORT="$2"
            shift 2
            ;;
        --port=*) PORT="${1#--port=}"; shift ;;
        --*) usage ;;
        *)
            if [ "$HEX_SET" -ne 0 ]; then usage; fi
            HEX="$1"
            HEX_SET=1
            shift
            ;;
    esac
done

case "$HEX" in
    /*) ;;
    *) HEX="$ROOT/$HEX" ;;
esac

if [ -z "$PORT" ]; then
    PORT="$(detect_port || true)"
fi

[ -f "$HEX" ] || {
    echo "error: application HEX not found: $HEX" >&2
    echo "build it first: tools/build_firmware.sh" >&2
    exit 1
}
[ -n "$PORT" ] || {
    echo "error: USB-UART adapter not found" >&2
    echo "use: tools/flash_firmware.sh --port /dev/..." >&2
    exit 1
}

command -v python3 >/dev/null 2>&1 || {
    echo "error: python3 not found" >&2
    exit 1
}

if [ "$ERASE" -eq 1 ] && [ "${DPLS_ALLOW_FACTORY_ERASE:-0}" != "1" ]; then
    cat >&2 <<'EOF'
ОТКАЗ: --erase стирает ВЕСЬ flash, включая factory identity, serial и BLE keys.
Если полный erase действительно нужен на непровиженной плате, повторите с
DPLS_ALLOW_FACTORY_ERASE=1. На серийной плате используйте обычную прошивку.
EOF
    exit 2
fi

# PySerial ставится локально в репозиторий: системный Python не загрязняем.
if ! PYTHONPATH="$ROOT/.python-deps" python3 -c 'import serial' >/dev/null 2>&1; then
    echo "==> Installing pyserial 3.5 locally"
    mkdir -p "$ROOT/.python-deps"
    if ! python3 -m pip --version >/dev/null 2>&1; then
        python3 -m ensurepip --user >/dev/null
    fi
    python3 -m pip install --disable-pip-version-check --quiet \
        --target "$ROOT/.python-deps" 'pyserial==3.5'
fi

PROGRAMMER="$ROOT/third_party/phy62x2/Utils/rdwr_phy62x2.py"
ARGS=(-p "$PORT")
if [ "$ERASE" -eq 1 ]; then
    echo "WARNING: full chip erase explicitly enabled; factory identity will be lost." >&2
    ARGS+=(-a)
fi
ARGS+=(wh "$HEX")

echo "port: $PORT"
echo "hex:  $HEX"
if [ "$AUTO_RST" -eq 1 ]; then
    echo "ROM entry: AUTO — RTS->RST_N, DTR->TM, UXTDWU@9600"
else
    echo "ROM entry: MANUAL — hold KEY1 and reset/power-cycle now; no Enter"
    echo "           for unattended flashing use --auto-rst"
fi

# Используем vendor flash protocol без копии. Подменяем только вход в ROM:
# - одинаковый bounded handshake для manual/auto;
# - auto управляет RTS/DTR;
# - manual вообще не трогает control lines.
# Reset здесь намеренно НЕ передаём: сначала будет отдельный readback/barrier.
PYTHONPATH="$ROOT/.python-deps" python3 - "$PROGRAMMER" "$AUTO_RST" "${ARGS[@]}" <<'PY'
import importlib.util
import sys
import time

programmer = sys.argv[1]
auto_rst = sys.argv[2] == "1"
programmer_args = sys.argv[3:]

spec = importlib.util.spec_from_file_location("phy62x2_programmer", programmer)
if spec is None or spec.loader is None:
    raise SystemExit(f"cannot load programmer: {programmer}")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

original_connect = module.phyflasher.Connect


def enter_rom(self):
    self._port.baudrate = module.START_BAUD
    self._port.timeout = 0.04

    if auto_rst:
        self._port.setRTS(True)   # RST_N low
        self._port.setDTR(True)   # TM low
        time.sleep(0.15)
        self._port.reset_output_buffer()
        self._port.reset_input_buffer()
        self._port.setDTR(False)  # TM high
        self._port.setRTS(False)  # RST_N high
        time.sleep(0.02)
    else:
        self._port.reset_output_buffer()
        self._port.reset_input_buffer()

    last = b""
    for _ in range(250):
        self._port.write(b"UXTDWU")
        last = self._port.read(6)
        if last == b"cmd>>:":
            return True
        if last == b"fct>>:":
            print("error: chip entered FCT mode", file=sys.stderr)
            return False

    if auto_rst:
        print(
            "error: ROM did not answer UXTDWU@9600 after automatic reset\n"
            "       auto mode requires physical RTS->RST_N and DTR->TM wiring;\n"
            "       TX/RX alone cannot reset a running application into ROM",
            file=sys.stderr,
        )
    else:
        print(
            "error: ROM did not answer UXTDWU@9600\n"
            "       hold KEY1, reset/power-cycle the board while the script is running",
            file=sys.stderr,
        )
    if last:
        print(f"       last reply: {last!r}", file=sys.stderr)
    return False


def controlled_connect(self, baud=module.DEF_RUN_BAUD):
    if self.next:
        return original_connect(self, baud)
    if not enter_rom(self):
        self._port.close()
        raise SystemExit(4)

    print("Chip Reset Ok. Response: b'cmd>>:'")

    # Переиспользуем vendor ReadRevision через его штатный --next branch.
    self.next = True
    try:
        if not original_connect(self, module.START_BAUD):
            return False
    finally:
        self.next = False

    if not self.FlashUnlock():
        self._port.close()
        raise SystemExit(4)
    print(self.chip, "- connected Ok")
    return self.SetBaud(baud)


module.phyflasher.Connect = controlled_connect
sys.argv = [programmer, *programmer_args]
module.main()
PY

# Не делаем reset непосредственно после последнего cpbin. ROM остаётся активен
# на 115200. Новым --next подключением читаем XIP, чем одновременно проверяем
# запись и переводим flash controller обратно в гарантированно читаемое состояние.
READBACK="$(mktemp -t dpls-post-flash.XXXXXX.bin)"
trap 'rm -f "$READBACK"' EXIT
sleep 0.05
PYTHONPATH="$ROOT/.python-deps" python3 "$PROGRAMMER" \
    -p "$PORT" -n -r rc "$VERIFY_ADDR" "$VERIFY_SIZE" "$READBACK"

# Сравниваем readback с теми же адресами Intel HEX. Это не просто задержка:
# если ROM записал не те байты, прошивка считается неуспешной и reset не маскирует ошибку.
python3 - "$HEX" "$READBACK" "$VERIFY_ADDR" "$VERIFY_SIZE" <<'PY'
from pathlib import Path
import sys

hex_path = Path(sys.argv[1])
readback_path = Path(sys.argv[2])
start = int(sys.argv[3], 0)
size = int(sys.argv[4], 0)

memory: dict[int, int] = {}
upper = 0
for raw_line in hex_path.read_text(encoding="ascii").splitlines():
    line = raw_line.strip()
    if not line:
        continue
    if not line.startswith(":"):
        raise SystemExit(f"invalid Intel HEX line: {line[:32]!r}")
    record = bytes.fromhex(line[1:])
    length = record[0]
    address = (record[1] << 8) | record[2]
    kind = record[3]
    data = record[4 : 4 + length]
    if kind == 0x04:
        if len(data) != 2:
            raise SystemExit("invalid extended linear address record")
        upper = int.from_bytes(data, "big") << 16
    elif kind == 0x00:
        absolute = upper + address
        for offset, value in enumerate(data):
            memory[absolute + offset] = value
    elif kind == 0x01:
        break

missing = [addr for addr in range(start, start + size) if addr not in memory]
if missing:
    raise SystemExit(
        f"post-flash verify: HEX has no data at 0x{missing[0]:08X}; "
        "VERIFY_ADDR must point to application XIP"
    )
expected = bytes(memory[addr] for addr in range(start, start + size))
actual = readback_path.read_bytes()
if len(actual) != size:
    raise SystemExit(f"post-flash verify: expected {size} bytes, read {len(actual)}")
if actual != expected:
    raise SystemExit(
        "post-flash verify: MISMATCH\n"
        f"  expected: {expected.hex()}\n"
        f"  actual:   {actual.hex()}"
    )
print(f"post-flash readback: PASS — 0x{start:08X}..0x{start + size - 1:08X}")
PY

echo "flash finalize: readback verified, reset sent after XIP barrier"
