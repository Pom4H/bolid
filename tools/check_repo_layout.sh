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

# PHY6252 target — единственная реальная hardware implementation в этом repo.
test -f firmware/phy6252/dpls_phy6252_app.c
test -f firmware/targets/phy6252/Makefile
test -f firmware/targets/phy6252/test-dpls.cproject.yml
test ! -e .gitmodules
test ! -e third_party/phy6252-emu
test ! -e firmware/phy6252_emu
test ! -e firmware/zmu

# Production target behavior проверяется внешним emulator.
grep -q 'uses: Pom4H/firmverse@v1' .github/workflows/ci.yml
grep -q 'board: pb03f-kit' .github/workflows/ci.yml
grep -q "strict: 'true'" .github/workflows/ci.yml

# Прошивка — один application image и один wrapper для manual/auto ROM entry.
test -f tools/flash_firmware.sh
test ! -e tools/flash_firmware_agent.sh
grep -q -- '--auto-rst' tools/flash_firmware.sh
! grep -q 'factory.bin\|0x3F000\|-r we' tools/flash_firmware.sh

echo 'Repository layout: PASS'
