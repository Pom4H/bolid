#!/usr/bin/env bash
# Build the single production PHY6252 Test-DPLS image with Arm Compiler 6.
#
#   tools/build_firmware.sh [output.hex]
#
# Toolchain activation is intentionally owned by the caller (GitHub Actions or
# the developer shell). This script owns only the product build.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/firmware/targets/phy6252"
OUT="${1:-$ROOT/tmp/test-dpls.hex}"
REGION_ROOT=""
PROFILE="${DPLS_BUILD_PROFILE:-production}"
CONNECTED_SLEEP="${DPLS_CONNECTED_SLEEP:-1}"
SOLUTION="$TARGET/test-dpls.csolution.yml"
TEMP_PROJECT=""
TEMP_SOLUTION=""
BUILD_LOG="$ROOT/tmp/firmware-ac6-${PROFILE}.log"

usage() {
    echo "usage: tools/build_firmware.sh [output.hex]" >&2
    exit 2
}

if [ "$#" -gt 1 ]; then usage; fi
if [ "$#" -eq 1 ] && { [ "$1" = "-h" ] || [ "$1" = "--help" ]; }; then usage; fi
case "$OUT" in
    /*) ;;
    *) OUT="$ROOT/$OUT" ;;
esac

cleanup() {
    if [ -n "$REGION_ROOT" ]; then rm -rf "$REGION_ROOT"; fi
    if [ -n "$TEMP_PROJECT" ]; then rm -f "$TEMP_PROJECT"; fi
    if [ -n "$TEMP_SOLUTION" ]; then rm -f "$TEMP_SOLUTION"; fi
}
trap cleanup EXIT

case "$PROFILE" in
    production) ;;
    debug-rom)
        case "$CONNECTED_SLEEP" in
            0|1) ;;
            *) echo "error: DPLS_CONNECTED_SLEEP must be 0 or 1" >&2; exit 2 ;;
        esac
        PROFILE_ID="$$"
        TEMP_PROJECT="$TARGET/test-dpls-debug-$PROFILE_ID.cproject.yml"
        TEMP_SOLUTION="$TARGET/test-dpls-debug-$PROFILE_ID.csolution.yml"
        python3 - "$TARGET/test-dpls.cproject.yml" "$TEMP_PROJECT" \
            "$TARGET/test-dpls.csolution.yml" "$TEMP_SOLUTION" \
            "$PROFILE_ID" "$CONNECTED_SLEEP" <<'PY'
from pathlib import Path
import sys

project_source, project_output, solution_source, solution_output, profile_id, connected_sleep = sys.argv[1:]
project = Path(project_source).read_text(encoding="utf-8")
replacements = {
    '- DEBUG_INFO: "0"': '- DEBUG_INFO: "1"',
    '- DPLS_CONNECTED_SLEEP: "1"': f'- DPLS_CONNECTED_SLEEP: "{connected_sleep}"',
    '- DPLS_DEBUG_UART_ROM: "0"': '- DPLS_DEBUG_UART_ROM: "1"',
    '- DPLS_POWER_DIAG_LOG: "0"': '- DPLS_POWER_DIAG_LOG: "1"',
}
for old, new in replacements.items():
    if project.count(old) != 1:
        raise SystemExit(f"error: diagnostic profile expected exactly one {old!r}")
    project = project.replace(old, new)
Path(project_output).write_text(project, encoding="utf-8")

solution = Path(solution_source).read_text(encoding="utf-8")
old_project = "project: test-dpls.cproject.yml"
new_project = f"project: test-dpls-debug-{profile_id}.cproject.yml"
if solution.count(old_project) != 1:
    raise SystemExit("error: diagnostic profile could not locate the CMSIS project")
Path(solution_output).write_text(solution.replace(old_project, new_project), encoding="utf-8")
PY
        SOLUTION="$TEMP_SOLUTION"
        ;;
    *) echo "error: unsupported DPLS_BUILD_PROFILE: $PROFILE" >&2; exit 2 ;;
esac

for tool in cbuild fromelf; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "$tool not found. Activate CMSIS-Toolbox + Arm Compiler 6.24.0 using:" >&2
        echo "  $TARGET/vcpkg-configuration.json" >&2
        exit 1
    fi
done

bash "$ROOT/tools/fetch_phy6252_sdk.sh"
mkdir -p "$(dirname "$OUT")" "$ROOT/tmp"
rm -rf "$TARGET/out" "$TARGET/tmp" "$TARGET/RTE"
mkdir -p "$TARGET/out"
ln -s ../../../sdk "$TARGET/out/sdk"

cbuild "$SOLUTION" --packs --update-rte 2>&1 | tee "$BUILD_LOG"
if grep -E ': warning:|Warning: [LA][0-9]|[1-9][0-9]* warning(s)? generated' "$BUILD_LOG"; then
    echo "error: firmware build produced warnings" >&2
    exit 1
fi

AXF="$(find "$TARGET/out" -type f -name '*.axf' | head -n 1)"
if [ -z "$AXF" ] || [ ! -f "$AXF" ]; then
    echo "Arm Compiler 6 target build completed without an AXF output" >&2
    exit 1
fi

REGION_ROOT="$(mktemp -d)"
REGIONS="$REGION_ROOT/regions"
fromelf --i32 --output "$REGIONS" "$AXF"
for region in ER_ROM_XIP JUMP_TABLE ER_IROM1; do
    if [ ! -f "$REGIONS/$region" ]; then
        echo "Missing fromelf region: $region" >&2
        exit 1
    fi
done

grep -v '^:00000001FF' "$REGIONS/ER_ROM_XIP" | grep -v '^:04000005' > "$OUT"
grep -v '^:00000001FF' "$REGIONS/JUMP_TABLE" | grep -v '^:04000005' >> "$OUT"
cat "$REGIONS/ER_IROM1" >> "$OUT"

if [ ! -s "$OUT" ]; then
    echo "error: firmware build did not produce a non-empty HEX: $OUT" >&2
    exit 1
fi

# Persistent flash belongs to the running device. A normal application image
# must never contain SNV or factory-sector bytes.
python3 - "$OUT" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
PERSISTENT_START = 0x1103C000
PERSISTENT_END = 0x11040000
upper = 0

for line_number, raw in enumerate(path.read_text(encoding="ascii").splitlines(), 1):
    line = raw.strip()
    if not line:
        continue
    if not line.startswith(":"):
        raise SystemExit(f"error: invalid Intel HEX line {line_number}")
    record = bytes.fromhex(line[1:])
    if len(record) < 5:
        raise SystemExit(f"error: short Intel HEX record at line {line_number}")
    length = record[0]
    address = (record[1] << 8) | record[2]
    kind = record[3]
    data = record[4 : 4 + length]
    if len(data) != length:
        raise SystemExit(f"error: truncated Intel HEX record at line {line_number}")
    if kind == 0x04:
        if length != 2:
            raise SystemExit(f"error: invalid type-04 record at line {line_number}")
        upper = int.from_bytes(data, "big") << 16
    elif kind == 0x00 and length:
        start = upper + address
        end = start + length
        if start < PERSISTENT_END and end > PERSISTENT_START:
            raise SystemExit(
                "error: firmware HEX overlaps persistent flash: "
                f"0x{start:08X}..0x{end - 1:08X} at line {line_number}; "
                "reserved 0x1103C000..0x1103FFFF"
            )

print("persistent flash guard: PASS")
PY

echo "toolchain: Arm Compiler 6.24.0"
echo "profile: $PROFILE"
echo "axf: $AXF"
echo "hex: $OUT"
