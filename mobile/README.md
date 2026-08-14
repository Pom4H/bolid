# Mobile

Test-DPLS has one Kotlin Multiplatform application surface for Android and iOS.

## Where code belongs

| Path | Responsibility |
|---|---|
| `core/src/commonMain/` | Shared Compose UI, protocol/CRC, auth contracts, binary parsers, domain and session state |
| `core/src/commonTest/` | Cross-platform protocol/crypto/session tests |
| `core/src/iosMain/` | Thin CoreBluetooth transport/controller and Compose `UIViewController` entry point |
| `android/` | Android BluetoothGatt/service/permission adapter and Activity/ViewModel shell |
| `ios/` | Xcode project, plist/assets and minimal Swift bootstrap only |

The rule is simple: **if Android and iOS should produce the same answer or show the same application screen, put it in `commonMain`.** Platform source sets only translate operating-system APIs into the shared controller/domain model.

There is no SwiftUI copy of the application and no second Swift protocol implementation.

## Entry points

Android:

```text
MainActivity
  → MainViewModel : DplsController
  → DplsApp (commonMain Compose)
```

iOS:

```text
TestDPLSApp.swift (~bootstrap)
  → IosAppKt.MainViewController()
  → IosDplsController + IosBleTransport (iosMain)
  → DplsApp (commonMain Compose)
```

The generated Apple framework is named `DplsCore`.

## Fastest local workflow

From the repository root:

```sh
bash tools/check_mobile.sh
```

The script runs shared tests, Android tests/coverage/lint and assembles the debug APK. On macOS it additionally executes Kotlin/Native simulator tests and links `DplsCore`.

Individual commands:

```sh
cd mobile
./gradlew :core:testDebugUnitTest
./gradlew :android:testDebugUnitTest :android:koverVerifyDebug
./gradlew :core:lintDebug :android:lintDebug :android:assembleDebug
```

macOS/iOS:

```sh
./gradlew :core:iosSimulatorArm64Test
./gradlew :core:linkDebugFrameworkIosSimulatorArm64
open ios/TestDPLS.xcodeproj
```

## Adding a feature

- New screen or shared presentation logic → `core/src/commonMain/.../app/`.
- New protocol field/message → `core/src/commonMain/.../protocol/` plus `commonTest` byte-contract tests.
- New deterministic session rule → `core/src/commonMain/.../session/` plus reducer/runtime tests.
- Android Bluetooth/lifecycle quirk → `android/` only.
- iOS CoreBluetooth/lifecycle quirk → `core/src/iosMain/` only.
- Xcode signing/assets/capabilities → `ios/` only.

Avoid adding compatibility facades unless an existing public call site actually requires one.

## Test strategy

The reusable layer carries the behavior-heavy tests:

- CRC-16/CCITT-FALSE known-answer vectors;
- encode/decode round trips for every message type;
- randomized malformed input and valid payloads;
- state/device-info/journal/control-message binary contracts;
- SHA-256/HMAC/PBKDF2 known-answer vectors;
- session reset and safety transitions.

The same common tests execute for Android/JVM and Kotlin/Native. Platform tests focus on integration rather than re-testing the same protocol rules twice.

## BLE service

| Item | UUID |
|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` |
| RX / WRITE | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` |
| TX / INDICATE+NOTIFY | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` |

A mode is application-visible only after firmware returns `COMMAND_RESULT` and the following `STATE_REPORT`; a successful GATT write is never treated as proof of hardware state.
