#!/bin/bash
# Сборка прошивки Test-DPLS (cbuild + ARM Compiler 6) и склейка прошивочного hex.
#
#   tools/build_firmware.sh [выходной.hex]      (по умолчанию tmp/test-dpls.hex)
#
# Toolchain ставится через `vcpkg activate` в каталоге ac6 (vcpkg-configuration.json).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ART="$HOME/.vcpkg/artifacts/2139c4c6"

# У части артефактов бинарники лежат в <версия>/bin, у ninja — прямо в <версия>/.
first_bin() { { ls -d "$1"/*/bin "$1"/*/ 2>/dev/null || true; } | sort -V | tail -1; }
TOOLBOX="$(first_bin "$ART/tools.open.cmsis.pack.cmsis.toolbox")"
CMAKE="$(first_bin "$ART/tools.kitware.cmake")"
NINJA="$(first_bin "$ART/tools.ninja.build.ninja")"
AC6="$(first_bin "$ART/compilers.arm.armclang")"
[ -n "$TOOLBOX" ] && [ -n "$AC6" ] || { echo "toolchain не найден в $ART — выполните vcpkg activate в ac6/" >&2; exit 1; }
export PATH="$TOOLBOX:$CMAKE:$NINJA:$AC6:$PATH"
AC6_VER="$(basename "$(dirname "$AC6")" | tr . _)"
export "AC6_TOOLCHAIN_${AC6_VER}=$AC6"

SOL="$ROOT/Firmware/sdk/release_bbb_sdk-PHY62XX_SDK_3.1.1/example/ble_peripheral/simpleBlePeripheral/ac6"
cbuild "$SOL/simpleBlePeripheral.csolution.yml" 2>&1 | grep -vE "warning csolution: absolute path"

TGT="$SOL/out/simpleBlePeripheral/Target_1"
OUT="${1:-$ROOT/tmp/test-dpls.hex}"
REGIONS="$(mktemp -d)/regions"
fromelf --i32 --output "$REGIONS" "$TGT/simpleBlePeripheral.axf"

# fromelf кладёт запись начального адреса (:04000005) в конец КАЖДОГО
# регионального hex, а парсер pvvx-флешера обрывает разбор на первой же
# такой записи: сегменты после неё теряются и плата не загружается.
# Оставляем только финальную (в ER_IROM1).
mkdir -p "$(dirname "$OUT")"
grep -v "^:00000001FF" "$REGIONS/ER_ROM_XIP" | grep -v "^:04000005" > "$OUT"
grep -v "^:00000001FF" "$REGIONS/JUMP_TABLE" | grep -v "^:04000005" >> "$OUT"
cat "$REGIONS/ER_IROM1" >> "$OUT"
rm -rf "$(dirname "$REGIONS")"
echo "hex: $OUT"
