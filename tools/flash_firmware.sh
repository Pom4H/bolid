#!/usr/bin/env bash
# Прошивка PB-03F через PHY62x2 ROM UART monitor.
#
#   tools/flash_firmware.sh [application.hex] [--auto-rst] [--erase] [--port /dev/...]
#
# Без --auto-rst скрипт не трогает RTS/DTR: KEY1/reset остаются ручными.
# С --auto-rst programmer сам делает RTS->RST_N, DTR->TM и входит в ROM.
# Enter нигде не требуется.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEX="$ROOT/tmp/test-dpls.hex"
HEX_SET=0
AUTO_RST=0
ERASE=0
PORT="${PORT:-}"

usage() {
    cat >&2 <<'EOF'
usage: tools/flash_firmware.sh [application.hex] [--auto-rst] [--erase] [--port /dev/...]

Defaults:
  application.hex: tmp/test-dpls.hex
  port:            first detected USB-UART

Modes:
  default      KEY1/reset вручную, без Enter
  --auto-rst   RTS->RST_N + DTR->TM, KEY1 не нужен
  --erase      chip erase перед записью; стирает SNV/bonds
EOF
    exit 2
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
        -h|--help) usage ;;
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

if [ "$AUTO_RST" -eq 0 ]; then
    ARGS+=(--manual-entry)
fi
if [ "$ERASE" -eq 1 ]; then
    echo "WARNING: --erase erases the chip including SNV/bonds." >&2
    ARGS+=(-a)
fi
ARGS+=(wh "$HEX")

echo "port: $PORT"
echo "hex:  $HEX"
if [ "$AUTO_RST" -eq 1 ]; then
    echo "ROM entry: automatic RTS->RST_N, DTR->TM, UXTDWU@9600"
else
    echo "ROM entry: hold KEY1 and reset/power-cycle; UXTDWU@9600 is sent automatically"
fi

PYTHONPATH="$ROOT/.python-deps" exec python3 "$PROGRAMMER" "${ARGS[@]}"
