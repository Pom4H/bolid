#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/mobile"

./gradlew \
  :core:testDebugUnitTest \
  :android:testDebugUnitTest \
  :android:koverVerifyDebug \
  :core:lintDebug \
  :android:lintDebug \
  :android:assembleDebug

if [[ "$(uname -s)" == "Darwin" ]]; then
  ./gradlew \
    :core:iosSimulatorArm64Test \
    :core:linkDebugFrameworkIosSimulatorArm64
else
  echo "iOS checks skipped (macOS required)"
fi
