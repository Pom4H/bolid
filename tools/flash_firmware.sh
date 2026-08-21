#!/usr/bin/env bash
# Прошивка PB-03F через PHY62x2 ROM UART monitor.
#
#   tools/flash_firmware.sh [application.hex] [--auto-rst] [--erase] [--port /dev/...]
#
# Без --auto-rst: KEY1/reset выполняются вручную, Enter не нужен.
# С --auto-rst: RTS -> RST_N, DTR -> TM. Эти линии должны быть физически подключены.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEX="$ROOT/tmp/test-dpls.hex"
HEX_SET=0
AUTO_RST=0
ERASE=0
PORT="${PORT:-}"

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
  --erase      chip erase before programming; erases SNV/bonds
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
ARGS=(-p "$PORT" -r)
if [ "$ERASE" -eq 1 ]; then
    echo "WARNING: --erase erases the chip including SNV/bonds." >&2
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
PYTHONPATH="$ROOT/.python-deps" exec python3 - "$PROGRAMMER" "$AUTO_RST" "${ARGS[@]}" <<'PY'
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
        # Штатная последовательность PHY62x2 utility.
        self._port.setRTS(True)   # RST_N low
        self._port.setDTR(True)   # TM low
        time.sleep(0.15)
        self._port.reset_output_buffer()
        self._port.reset_input_buffer()
        self._port.setDTR(False)  # TM high
        self._port.setRTS(False)  # RST_N high
        time.sleep(0.02)
    else:
        # Не меняем RTS/DTR: оператор сам переводит плату через KEY1/reset.
        self._port.reset_output_buffer()
        self._port.reset_input_buffer()

    # Upstream utility использует 250 попыток (~10 секунд). Этого достаточно,
    # чтобы поймать reset window, но ошибка не выглядит как вечное зависание.
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
