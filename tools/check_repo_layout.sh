#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

required=(
  firmware
  mobile/android
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

for path in "${legacy[@]}"; do
  test ! -e "$path" || { echo "legacy directory must not exist: $path" >&2; exit 1; }
done

# Gradle module names are part of the public repository architecture.
grep -q 'include(":android")' mobile/settings.gradle.kts
grep -q 'include(":core")' mobile/settings.gradle.kts
! grep -q 'include(":app")' mobile/settings.gradle.kts
! grep -q 'include(":shared")' mobile/settings.gradle.kts

# One shared application/controller owns cross-platform behavior.
test -f mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsApp.kt
test -f mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt
test -f mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsTransport.kt
test -f mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsProtocol.kt
test -f mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/session/DplsSession.kt

# Platform source contains adapters only, never a second app/protocol implementation.
test -f mobile/android/src/main/java/ru/bolid/testdpls/ble/AndroidBleTransport.kt
test -f mobile/core/src/iosMain/kotlin/ru/bolid/testdpls/core/app/IosBleTransport.kt
test -f mobile/ios/TestDPLS/TestDPLSApp.swift

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
# Android BLE package is intentionally one transport implementation.
test "$(find mobile/android/src/main/java/ru/bolid/testdpls/ble -type f -name '*.kt' | wc -l | tr -d ' ')" = "1"

# PHY6252 integration must use the supported SDK boundary instead of reaching
# into Link Layer RAM or an obsolete raw-MAC flash slot. Target manifests must
# not re-add drivers that DPLS does not use.
identity=firmware/phy6252/dpls_ble_identity.c
gnu_target=firmware/targets/phy6252/Makefile
ac6_target=firmware/targets/phy6252/test-dpls.cproject.yml
grep -q 'HCI_EXT_SetBDADDRCmd' "$identity"
grep -q 'check_chip_mAddr' "$identity"
! grep -q '0x1fff0965' "$identity"
! grep -q 'DPLS_CHIP_MAC_FLASH_ADDR' "$identity"
! grep -q '0x4000u' "$identity"
for driver in key pwm led_light; do
  ! grep -q "components/driver/$driver" "$gnu_target"
  ! grep -q "components/driver/$driver" "$ac6_target"
done

echo "OK: repository layout and ownership boundaries"
