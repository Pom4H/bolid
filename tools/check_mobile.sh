#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/mobile"

./gradlew \
  :wire:testDebugUnitTest \
  :runtime:testDebugUnitTest \
  :core:testDebugUnitTest \
  :wire:lintDebug \
  :runtime:lintDebug \
  :core:lintDebug \
  :android:lintDebug \
  :android:assembleDebug

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "iOS checks skipped (macOS required)"
  exit 0
fi

./gradlew \
  :wire:iosSimulatorArm64Test \
  :runtime:iosSimulatorArm64Test \
  :core:iosSimulatorArm64Test \
  :core:linkDebugFrameworkIosSimulatorArm64

UDID="$(xcrun simctl list devices available -j | python3 -c '
import json,sys
data=json.load(sys.stdin)["devices"]
for runtime in data.values():
    for device in runtime:
        if device.get("isAvailable") and device.get("name", "").startswith("iPhone"):
            print(device["udid"])
            raise SystemExit
raise SystemExit("no available iPhone simulator")
')"

xcodebuild test \
  -project ios/TestDPLS.xcodeproj \
  -scheme TestDPLS \
  -destination "platform=iOS Simulator,id=$UDID" \
  CODE_SIGNING_ALLOWED=NO
