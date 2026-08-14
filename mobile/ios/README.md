# Test-DPLS iOS host

This directory is intentionally small. The product controller, UI, Test-DPLS protocol/session logic and CoreBluetooth adapter are Kotlin code in `../core/`.

## What remains here

| Path | Responsibility |
|---|---|
| `TestDPLS/TestDPLSApp.swift` | Minimal Swift/Xcode bootstrap that displays the Compose `UIViewController` |
| `TestDPLS/Info.plist` | Bluetooth permissions, background mode and app metadata |
| `TestDPLS/Resources/` | App icon and assets |
| `TestDPLSTests/` | Native smoke test for the exported KMP entry point |
| `TestDPLS.xcodeproj/` | Signing, build settings and Gradle framework integration |

There is deliberately no Swift BLE client, protocol codec, domain model, crypto implementation, application controller or duplicate SwiftUI screen tree.

The iOS path is:

```text
../core/src/commonMain/.../DplsClient.kt      shared product controller
../core/src/commonMain/.../DplsApp.kt         shared Android+iOS UI
../core/src/iosMain/.../IosBleTransport.kt    CoreBluetooth callbacks + writes
../core/src/iosMain/.../IosPlatform.kt        Apple clock + secure random
../core/src/iosMain/.../IosApp.kt             Compose UIViewController entry point
TestDPLS/TestDPLSApp.swift                    tiny Xcode bootstrap
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

Or run the complete mobile loop from the repository root:

```sh
bash tools/check_mobile.sh
```

For a real iPhone, choose a development team in Signing & Capabilities and run the `TestDPLS` scheme. Simulator checks validate compilation, common tests and host integration; physical BLE behavior still requires real hardware.

## iOS-specific rules

- iOS device identity exposed to the app is `CBPeripheral.identifier`; applications do not receive a BLE MAC address.
- CoreBluetooth owns ATT MTU negotiation; `IosBleTransport` uses the maximum write length reported for `.withResponse`.
- Pairing is initiated by iOS when protected GATT access requires it.
- CoreBluetooth callbacks stay in `iosMain`; product behavior and screens stay in `commonMain`.
- Do not add Swift wrappers around common Kotlin APIs unless an Apple framework genuinely requires a Swift-only boundary.
