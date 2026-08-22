#!/usr/bin/env bash
# Build and flash the opt-in diagnostic image through Firmverse. The first
# installation needs --initial-manual and one KEY1 press; subsequent updates
# enter ROM through the application UART handoff without touching the board.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIRMVERSE_ROOT="${FIRMVERSE_ROOT:-$(cd "$ROOT/../firmverse" 2>/dev/null && pwd || true)}"
HEX="$ROOT/tmp/test-dpls-debug-rom.hex"
PORT=""
INITIAL_MANUAL=0
BUILD=1
HANDOFF_TOKEN="00d544504c532d524f4da55ac33c7e81"
# PHY62xx boot-info wants the SDK vector/jump-table entry, not the ELF type-05
# Reset_Handler address emitted by objcopy. This matches the vendor `wh` path.
BOOT_START="0x1fff1838"

usage() {
    cat <<'EOF'
Usage: tools/flash_debug_firmware.sh [options]

Options:
  --port PORT       USB-UART port (Firmverse auto-detects it when omitted)
  --hex PATH        output/input diagnostic HEX path
  --no-build        flash an already-built HEX
  --initial-manual  first installation: wait for one KEY1/reset action
  -h, --help        show this help

Default mode uses the diagnostic firmware's UART BREAK + project token
handoff and therefore does not require KEY1.
EOF
}

while (($#)); do
    case "$1" in
        --port)
            PORT="${2:?--port requires a value}"
            shift 2
            ;;
        --hex)
            HEX="${2:?--hex requires a value}"
            shift 2
            ;;
        --no-build)
            BUILD=0
            shift
            ;;
        --initial-manual)
            INITIAL_MANUAL=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "error: unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ -z "$FIRMVERSE_ROOT" || ! -f "$FIRMVERSE_ROOT/Cargo.toml" ]]; then
    echo "error: Firmverse checkout not found; set FIRMVERSE_ROOT" >&2
    exit 2
fi

if ((BUILD)); then
    "$ROOT/tools/build_debug_firmware.sh" "$HEX"
elif [[ ! -f "$HEX" ]]; then
    echo "error: diagnostic HEX not found: $HEX" >&2
    exit 2
fi

ARGS=(
    run --release
    --manifest-path "$FIRMVERSE_ROOT/Cargo.toml"
    --bin phy6252-flash --
    "$HEX"
    --start "$BOOT_START"
)
if [[ -n "$PORT" ]]; then
    ARGS+=(--port "$PORT")
fi
if ((!INITIAL_MANUAL)); then
    ARGS+=(--application-handoff-token "$HANDOFF_TOKEN")
fi

echo "==> Flashing diagnostic image with Firmverse"
if ((INITIAL_MANUAL)); then
    echo "    mode=initial-manual (one KEY1/reset action required)"
else
    echo "    mode=application-handoff (KEY1 is not required)"
fi
exec cargo "${ARGS[@]}"
