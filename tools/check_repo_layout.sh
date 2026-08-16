#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

required=(
  firmware
  mobile/android
  mobile/wire
  mobile/runtime
  mobile/core
  mobile/ios
  docs
  tools
  third_party/phy62x2
)
legacy=(
  Firmware
  TestDPLS
  TestDPLS-iOS
  pvvx-PHY62x2
)

for path in "${required[@]}"; do
  test -d "$path" || { echo "missing required directory: $path" >&2; exit 1; }
done

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

legacy_is_required() {
  local candidate="$1"
  local req
  for req in "${required[@]}"; do
    if [[ "$(lower "$candidate")" == "$(lower "$req")" ]]; then
      return 0
    fi
  done
  return 1
}

for path in "${legacy[@]}"; do
  # macOS is case-insensitive: Firmware and firmware are the same directory.
  if legacy_is_required "$path"; then
    continue
  fi
  if git ls-files -- "$path" | grep -q .; then
    echo "legacy path must not be tracked: $path" >&2
    exit 1
  fi
done

# Gradle module names are part of the public repository architecture.
grep -q 'include(":wire")' mobile/settings.gradle.kts
grep -q 'include(":runtime")' mobile/settings.gradle.kts
grep -q 'include(":core")' mobile/settings.gradle.kts
grep -q 'include(":android")' mobile/settings.gradle.kts
! grep -q 'include(":app")' mobile/settings.gradle.kts
! grep -q 'include(":shared")' mobile/settings.gradle.kts

# Dependency zones are represented by physical modules.
test -f mobile/wire/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsProtocol.kt
test -f mobile/wire/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsEncode.kt
test -f mobile/wire/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsDecode.kt
test -f mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/Link.kt
test -f mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/DeviceSession.kt
test -f mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/session/DplsSession.kt

# One shared application/controller owns cross-platform product behavior.
test -f mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsApp.kt
test -f mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt
test -f mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsTransport.kt

# Old monolithic owners must not silently reappear.
test ! -e mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsProtocol.kt
test ! -e mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/session/DplsSession.kt

# Platform BLE adapters are the implemented transport boundary in this PR.
# Future mesh/serial boundaries must arrive with the feature that consumes them,
# not as unused runtime scaffolding.
test -f mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsBle.kt
test -f mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsPlatformEffects.kt
test -f mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt
test -f mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidPlatformServices.kt
test -f mobile/core/src/iosMain/kotlin/ru/bolid/testdpls/core/app/IosBleTransport.kt
test -f mobile/core/src/iosMain/kotlin/ru/bolid/testdpls/core/app/IosPlatform.kt
test -f mobile/ios/TestDPLS/TestDPLSApp.swift
test ! -e mobile/android/src/main/java/ru/bolid/testdpls/ble
test ! -e mobile/android/src/main/java/ru/bolid/testdpls/ui/MainViewModel.kt

duplicates=(
  mobile/android/src/main/java/ru/bolid/testdpls/ble/BleClient.kt
  mobile/android/src/main/java/ru/bolid/testdpls/ble/DplsModels.kt
  mobile/android/src/main/java/ru/bolid/testdpls/ble/DplsWire.kt
  mobile/android/src/main/java/ru/bolid/testdpls/protocol/DplsProtocol.kt
  mobile/android/src/main/java/ru/bolid/testdpls/ui/DplsScreen.kt
  mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsInterop.kt
  mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsMessageBridge.kt
  mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/session/DplsSessionBridge.kt
  mobile/core/src/iosMain/kotlin/ru/bolid/testdpls/core/app/IosDplsController.kt
  mobile/ios/TestDPLS/BLE
  mobile/ios/TestDPLS/Protocol
  mobile/ios/TestDPLS/UI
)
for path in "${duplicates[@]}"; do
  test ! -e "$path" || { echo "duplicate application layer must not exist: $path" >&2; exit 1; }
done

# Production iOS host is intentionally one Swift bootstrap file.
test "$(find mobile/ios/TestDPLS -type f -name '*.swift' | wc -l | tr -d ' ')" = "1"
# Each OS has one BLE transport in the KMP product module.
test "$(find mobile/core/src/androidMain -type f -name 'AndroidBleTransport.kt' | wc -l | tr -d ' ')" = "1"
test "$(find mobile/core/src/iosMain -type f -name 'IosBleTransport.kt' | wc -l | tr -d ' ')" = "1"

# PHY6252 integration must use the supported SDK boundary instead of reaching
# into Link Layer RAM or an obsolete raw-MAC flash slot. Target manifests must
# not compile unused example drivers. The key include path is intentionally
# retained because vendor halPeripheral.h includes key.h transitively.
identity=firmware/phy6252/dpls_ble_identity.c
gnu_target=firmware/targets/phy6252/Makefile
ac6_target=firmware/targets/phy6252/test-dpls.cproject.yml
grep -q 'HCI_EXT_SetBDADDRCmd' "$identity"
grep -q 'check_chip_mAddr' "$identity"
! grep -q '0x1fff0965' "$identity"
! grep -q 'DPLS_CHIP_MAC_FLASH_ADDR' "$identity"
! grep -q '0x4000u' "$identity"
grep -q 'components/driver/key' "$gnu_target"
grep -q 'components/driver/key' "$ac6_target"
grep -q 'src/dpls_safety.c' "$gnu_target"
grep -q 'src/dpls_safety.c' "$ac6_target"
for source in 'key/key.c' 'pwm/pwm.c' 'led_light/led_light.c'; do
  ! grep -q "components/driver/$source" "$gnu_target"
  ! grep -q "components/driver/$source" "$ac6_target"
done

test -f tools/dpls_lab.sh
test -f tools/dpls-lab/hub.ts
test -f mobile/web/src/wasmJsMain/kotlin/ru/bolid/testdpls/web/LabBleTransport.kt
test ! -e tools/dpls-lab/src/protocol.ts
test ! -e tools/dpls-lab/src/crypto.ts
test ! -e tools/dpls-lab/src/ble.ts
