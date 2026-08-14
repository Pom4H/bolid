# Test-DPLS for iOS

Native iOS host for Test-DPLS. CoreBluetooth and SwiftUI stay platform-native; protocol framing and other cross-platform semantics are provided by the Kotlin Multiplatform `DplsCore` module in `../core/`.

## Boundary

| Layer | Responsibility |
|---|---|
| `../core/` | CRC, framing, binary contracts, domain/session primitives |
| `TestDPLS/Protocol/DplsProtocol.swift` | Thin Swift compatibility facade over `DplsCore` |
| `TestDPLS/BLE/BleClient.swift` | CoreBluetooth lifecycle and platform orchestration |
| `TestDPLS/BLE/DplsCrypto.swift` | Apple crypto adapter for PBKDF2/HMAC |
| `TestDPLS/UI/` | SwiftUI screens |
| `TestDPLSTests/` | Native iOS integration/compatibility tests |

`DplsProtocol.swift` does not implement a second CRC or frame codec. It converts small BLE frames to a primitive/String interop representation and delegates encode/decode/CRC to `DplsCore`.

## Xcode integration

The app target contains a `Build DplsCore` phase before `Compile Sources`. The phase runs:

```sh
cd "$SRCROOT/.."
./gradlew :core:embedAndSignAppleFrameworkForXcode
```

The app imports the resulting framework as `DplsCore`. User Script Sandboxing is disabled for the app target because the Gradle integration needs access to the shared build outputs.

## Build and test

Requirements: macOS, Xcode and Java 17.

```sh
open mobile/ios/TestDPLS.xcodeproj
```

For a simulator build from the repository root:

```sh
cd mobile
./gradlew :core:iosSimulatorArm64Test
./gradlew :core:linkDebugFrameworkIosSimulatorArm64
cd ..

xcodebuild test \
  -project mobile/ios/TestDPLS.xcodeproj \
  -scheme TestDPLS \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  CODE_SIGNING_ALLOWED=NO
```

For a real iPhone, select a development team in Signing & Capabilities and run the `TestDPLS` scheme. BLE behavior should be validated on hardware; the simulator is primarily for compilation, shared-core tests and UI/native unit tests.

## BLE service

| Item | UUID |
|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` |
| RX / WRITE | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` |
| TX / INDICATE+NOTIFY | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` |

The app treats a test mode as applied only after the device returns `COMMAND_RESULT` followed by the corresponding `STATE_REPORT`.

## iOS-specific behavior

- Device identity exposed to the UI is based on `CBPeripheral.identifier`; iOS does not expose a BLE MAC address to applications.
- Write capacity comes from `maximumWriteValueLength(for: .withResponse)`; the CoreBluetooth stack owns ATT MTU negotiation.
- Pairing is initiated by iOS when the app writes to the protected RX characteristic.
- Platform lifecycle/reconnect behavior remains in the native adapter; protocol compatibility must not depend on CoreBluetooth callbacks.
