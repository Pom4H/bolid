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

echo "OK: repository layout and ownership boundaries"
