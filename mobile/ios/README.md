# Test-DPLS iOS host

This directory is intentionally small. The iOS application UI, Test-DPLS protocol/session logic and CoreBluetooth implementation are Kotlin code in `../core/`.

## What remains here

| Path | Responsibility |
|---|---|
| `TestDPLS/TestDPLSApp.swift` | Minimal SwiftUI/Xcode bootstrap that displays the Compose `UIViewController` |
| `TestDPLS/Info.plist` | Bluetooth permissions, background mode and app metadata |
| `TestDPLS/Resources/` | App icon and assets |
| `TestDPLSTests/` | Native smoke test that verifies the exported KMP entry point |
| `TestDPLS.xcodeproj/` | Signing, build settings and Gradle framework integration |

There is deliberately no Swift BLE client, protocol codec, domain model, crypto implementation or duplicate SwiftUI screen tree.

The actual iOS implementation lives in:

```text
../core/src/iosMain/.../IosBleTransport.kt   CoreBluetooth callbacks + writes
../core/src/iosMain/.../IosDplsController.kt iOS lifecycle/controller adapter
../core/src/iosMain/.../IosApp.kt            Compose UIViewController entry point
../core/src/commonMain/.../DplsApp.kt         shared Android+iOS UI
```

## Xcode integration

The `Build DplsCore` phase runs before Swift compilation:

```sh
cd "$SRCROOT/.."
./gradlew :core:embedAndSignAppleFrameworkForXcode
```

Swift imports `DplsCore` and calls `IosAppKt.MainViewController()`. That is the entire application bridge.

Requirements: macOS, Xcode and Java 17.

```sh
cd mobile
./gradlew :core:iosSimulatorArm64Test
./gradlew :core:linkDebugFrameworkIosSimulatorArm64
open ios/TestDPLS.xcodeproj
```

Or from the repository root:

```sh
bash tools/check_mobile.sh
```

To run XCTest from the command line:

```sh
xcodebuild test \
  -project mobile/ios/TestDPLS.xcodeproj \
  -scheme TestDPLS \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  CODE_SIGNING_ALLOWED=NO
```

For a real iPhone, choose a development team in Signing & Capabilities and run the `TestDPLS` scheme. The simulator validates compilation, shared-core tests and host integration; BLE behavior must be accepted on real hardware.

## iOS-specific rules

- iOS device identity exposed to the app is `CBPeripheral.identifier`; applications do not receive a BLE MAC address.
- CoreBluetooth owns ATT MTU negotiation; the adapter uses the maximum write length reported for `.withResponse`.
- Pairing is initiated by iOS when protected GATT access requires it.
- CoreBluetooth callbacks must stay in `iosMain`; protocol parsing and application screens must stay in `commonMain`.
- Do not add Swift wrappers around common Kotlin APIs unless an Apple framework genuinely requires a Swift-only boundary.
