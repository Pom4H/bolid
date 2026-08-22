#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

for path in firmware mobile/wire mobile/runtime mobile/core mobile/android mobile/ios docs tools third_party/phy62x2; do
  test -d "$path" || { echo "missing required directory: $path" >&2; exit 1; }
done

# Один shared KMP stack; без дублирующих приложений/controllers.
grep -q 'include(":wire")' mobile/settings.gradle.kts
grep -q 'include(":runtime")' mobile/settings.gradle.kts
grep -q 'include(":core")' mobile/settings.gradle.kts
grep -q 'include(":android")' mobile/settings.gradle.kts
for path in \
  mobile/android/src/main/java/ru/bolid/testdpls/ble \
  mobile/android/src/main/java/ru/bolid/testdpls/ui/MainViewModel.kt \
  mobile/core/src/iosMain/kotlin/ru/bolid/testdpls/core/app/IosDplsController.kt \
  mobile/ios/TestDPLS/BLE \
  mobile/ios/TestDPLS/Protocol \
  mobile/ios/TestDPLS/UI; do
  test ! -e "$path" || { echo "duplicate application layer: $path" >&2; exit 1; }
done

test "$(find mobile/ios/TestDPLS -type f -name '*.swift' | wc -l | tr -d ' ')" = "1"
test "$(find mobile/core/src/androidMain -type f -name 'AndroidBleTransport.kt' | wc -l | tr -d ' ')" = "1"
test "$(find mobile/core/src/iosMain -type f -name 'IosBleTransport.kt' | wc -l | tr -d ' ')" = "1"

# PHY6252: только split runtime RC8. Монолит и промежуточные facade удалены физически.
for path in \
  firmware/phy6252/dpls_phy6252_runtime.c \
  firmware/phy6252/dpls_phy6252_transport.c \
  firmware/phy6252/dpls_phy6252_storage.c \
  firmware/phy6252/dpls_phy6252_measurements.c \
  firmware/phy6252/dpls_phy6252_outputs.c \
  firmware/phy6252/dpls_phy6252_auth.c \
  firmware/phy6252/dpls_phy6252_supervisor.c \
  firmware/targets/phy6252/Makefile \
  firmware/targets/phy6252/test-dpls.cproject.yml; do
  test -f "$path" || { echo "missing RC8 production source: $path" >&2; exit 1; }
done
for path in \
  firmware/phy6252/dpls_phy6252_app.c \
  firmware/phy6252/dpls_phy6252_app.h \
  firmware/phy6252/dpls_phy6252_snv_guard.c \
  firmware/phy6252/dpls_phy6252_snv_guard.h \
  firmware/phy6252/dpls_phy6252_storage_ble.c \
  firmware/phy6252/dpls_phy6252_storage_ble.h; do
  test ! -e "$path" || { echo "legacy PHY6252 layer returned: $path" >&2; exit 1; }
done

test ! -e .gitmodules
test ! -e third_party/phy6252-emu
test ! -e firmware/phy6252_emu
test ! -e firmware/zmu

# Production target behavior проверяется внешним emulator.
grep -q 'uses: Pom4H/firmverse@03422429fe25382ed95b2d96a7482bd43907b6eb' .github/workflows/ci.yml
grep -q 'board: pb03f-kit' .github/workflows/ci.yml
grep -q "strict: 'true'" .github/workflows/ci.yml

# Один application flasher; проверенный PB-03F path — ручной KEY1 + vendor wh.
# У штатного адаптера кита RTS/DTR не разведены, поэтому auto-reset в wrapper
# запрещён. Обычная прошивка не трогает factory sector, а --erase чистит только
# SNV work area и никогда не вызывает vendor all-chip erase.
test -f tools/flash_firmware.sh
test ! -e tools/flash_firmware_agent.sh
! grep -q -- '--auto-rst' tools/flash_firmware.sh
! grep -q 'setRTS\|setDTR\|controlled_connect' tools/flash_firmware.sh
! grep -q 'factory.bin\|0x3F000\|-r we' tools/flash_firmware.sh
! grep -q 'ARGS=(-p "$PORT" -a\|cmd_erase_all_flash' tools/flash_firmware.sh
grep -q 'er 0x3C000 0x3000' tools/flash_firmware.sh
grep -q -- '-r wh' tools/flash_firmware.sh

echo 'Repository layout: PASS'