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

test -f mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsProtocol.kt
test -f mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/session/DplsSession.kt

echo "OK: repository layout"
