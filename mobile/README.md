# Mobile

Test-DPLS has one Kotlin Multiplatform application for Android and iOS.

## Where code belongs

| Path | Responsibility |
|---|---|
| `core/src/commonMain/` | `DplsClient`, shared Compose UI, protocol/CRC/auth, binary parsers, domain and session runtime |
| `core/src/commonTest/` | Cross-platform controller/protocol/crypto/session tests |
| `core/src/androidMain/` | `AndroidBleTransport`, Android clock/prefs/alerts/BLE keep-alive |
| `core/src/iosMain/` | `IosBleTransport`, Apple clock/random/alerts and Compose `UIViewController` entry point |
| `android/` | permissions/Activity shell and debug E2E driver |
| `ios/` | Xcode project, plist/assets and one minimal Swift bootstrap |
| `web/` | wasm Compose host for `tools/dpls-lab` (`LabBleTransport`) |

Phone version is **1.4.1** (`versionName` / `MARKETING_VERSION`), matching firmware `DPLS_FW_VERSION_*`.

The rule is simple: **if Android and iOS should produce the same answer or show the same product behavior, put it in `commonMain`.** Platform code only translates operating-system APIs into `DplsTransport` events.

There is no SwiftUI copy, second protocol codec or second application controller.

## Entry points

Android:

```text
MainActivity
  → DplsApplication.client (DplsClient + Android adapters)
  → DplsApp (commonMain Compose)
```

iOS:

```text
TestDPLSApp.swift (bootstrap only)
  → IosAppKt.MainViewController()
  → DplsClient(IosBleTransport, IosPlatformServices)
  → DplsApp (commonMain Compose)
```

The generated Apple framework is named `DplsCore`.

## Fastest local workflow

From the repository root:

```sh
bash tools/check_mobile.sh
```

The script runs shared JVM tests, core/Android lint and assembles the debug APK. On macOS it also executes Kotlin/Native simulator tests, links `DplsCore` and runs the Xcode integration smoke test.

Individual commands:

```sh
cd mobile
./gradlew :core:testDebugUnitTest
./gradlew :core:lintDebug :android:lintDebug :android:assembleDebug
```

macOS/iOS:

```sh
./gradlew :core:iosSimulatorArm64Test
./gradlew :core:linkDebugFrameworkIosSimulatorArm64
open ios/TestDPLS.xcodeproj
```

## Adding a feature

- Screen/presentation or application flow → `core/src/commonMain/.../app/`.
- Protocol field/message/auth contract → `core/src/commonMain/.../protocol/` plus `commonTest` byte-contract tests.
- Secret/session runtime rule → `core/src/commonMain/.../session/`.
- Android Bluetooth/lifecycle quirk → `core/src/androidMain/`
- iOS CoreBluetooth/lifecycle quirk → `core/src/iosMain/`
- Xcode signing/assets/capabilities → `ios/` only.

An ordinary product feature should normally touch one shared Kotlin area. Do not add compatibility facades or platform controllers unless an OS API genuinely requires a new boundary.

## Test strategy

The reusable layer carries the behavior-heavy tests:

- CRC-16/CCITT-FALSE known-answer vectors;
- encode/decode round trips for every message type;
- 10,000 malformed/random decoder inputs and 2,000 random valid round trips;
- auth/command/state/device-info/journal binary contracts;
- SHA-256/HMAC/PBKDF2 known-answer vectors;
- shared `DplsClient` fake-transport flows including stale command-id rejection and Bluetooth-loss safety;
- session secret/reset tests.

The same common tests execute for JVM/Android and Kotlin/Native. Platform validation focuses on Bluetooth/framework integration rather than re-testing protocol rules twice.

Without a physical phone, `bash tools/soft_ble_e2e.sh` runs the product `DplsClient` against `dpls_simulator`. The lab wasm phone is the same UI over WebSocket; it is not a second client.

## BLE service

| Item | UUID |
|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` |
| RX / WRITE | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` |
| TX / INDICATE+NOTIFY | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` |

A mode is application-visible only after firmware returns the matching `COMMAND_RESULT` and the following `STATE_REPORT`; a successful GATT write is never treated as proof of hardware state.
