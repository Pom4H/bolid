# Mobile

Android and iOS clients for Test-DPLS share one Kotlin Multiplatform core.

## Modules

| Module | Responsibility |
|---|---|
| `core/` | Protocol framing, CRC, binary message parsing, domain types and session state |
| `android/` | BluetoothGatt lifecycle, Android permissions/bond recovery and Compose UI |
| `ios/` | CoreBluetooth lifecycle and SwiftUI host |

`core/` deliberately contains no Android `Context`, `BluetoothGatt`, CoreBluetooth object, wall-clock singleton or UI toolkit dependency. Platform APIs stay at the edges so protocol/session behavior remains deterministic and testable.

## Core targets

- Android/JVM
- iOS arm64
- iOS Simulator arm64

The Apple framework name is `DplsCore`.

## Build

```sh
./gradlew :core:testDebugUnitTest
./gradlew :android:testDebugUnitTest :android:koverVerifyDebug
./gradlew :android:lintDebug :android:assembleDebug
```

On macOS:

```sh
./gradlew :core:iosSimulatorArm64Test
./gradlew :core:linkDebugFrameworkIosSimulatorArm64
```

Open the native iOS host with:

```sh
open ios/TestDPLS.xcodeproj
```

## Test strategy

The shared core carries the behavior-heavy tests:

- CRC-16/CCITT-FALSE known-answer vector;
- encode/decode round trips for every message type;
- randomized malformed-input decoder tests;
- randomized payload round trips;
- binary `STATE_REPORT`, device-info and journal contracts;
- session safety transitions (link loss, command completion and failure paths).

Android Kover measures the small Android protocol compatibility facade with a 95% gate. It does **not** publish a misleading percentage for Bluetooth framework callbacks. Firmware, KMP behavior and platform integration are tested at their appropriate boundaries.

## BLE service

| Item | UUID |
|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` |
| RX / WRITE | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` |
| TX / INDICATE+NOTIFY | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` |

A mode is considered applied only after `COMMAND_RESULT` and the following `STATE_REPORT`; the UI never assumes that a successful GATT write means the hardware changed state.
