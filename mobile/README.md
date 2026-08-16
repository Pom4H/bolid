# Mobile

Test-DPLS uses one Kotlin Multiplatform product implementation for Android and iOS.

## Modules

| Module/path | Responsibility |
|---|---|
| `wire/` | protocol-v2 frames, CRC, endian helpers, auth crypto, advertisement parsing |
| `runtime/` | `NodeId`, `LinkEndpoint`, `DeviceSession`, frame sequencing |
| `core/src/commonMain/` | `DplsClient`, domain models/parsers, journal and shared Compose UI |
| `core/src/commonTest/` | shared product/parser/controller tests |
| `core/src/androidMain/` | Android BLE transport, preferences/alerts/keep-alive |
| `core/src/iosMain/` | iOS BLE transport, Apple platform services and Compose host |
| `android/` | Android Activity/permissions/debug E2E shell |
| `ios/` | Xcode project, plist/assets and minimal Swift bootstrap |

Dependency direction:

```text
:wire <- :runtime <- :core <- Android/iOS host
```

The rule is simple: if Android and iOS should produce the same product answer, keep it in shared Kotlin. Platform code translates operating-system APIs into the shared transport/services boundaries.

There is no SwiftUI copy, second protocol codec or second application controller.

## Runtime truth

`DeviceSession` is the only owner of link/auth/verified-identity lifecycle.

```text
Connecting -> Discovering -> Linked
  -> Commissioning / Authenticating
  -> Synchronizing
  -> Online(verified NodeId)
```

Authentication success does not immediately mean `Online`. Authenticated `DEVICE_INFO` must first prove the stable node identity.

`DplsUiState.phase`, `authenticated`, `initialized` and `credentialsReady` are projections only. Product/protocol decisions must not use those UI fields as authority.

See [`../docs/developer/mobile-runtime.md`](../docs/developer/mobile-runtime.md).

## Protocol correlation

Protocol v2 uses exactly one transaction id: `DplsProtocol.Frame.sequence`.

`DplsClient.Operation` stores the sequence of the current correlated operation. Responses/errors with another sequence cannot complete it. Old v1 `commandId` is decode compatibility only.

See [`../docs/developer/protocol-v2.md`](../docs/developer/protocol-v2.md).

## Entry points

Android:

```text
MainActivity
  -> DplsApplication.client (DplsClient + Android adapters)
  -> DplsApp (shared Compose)
```

iOS:

```text
TestDPLSApp.swift (bootstrap only)
  -> IosAppKt.MainViewController()
  -> DplsClient(IosBleTransport, IosPlatformServices)
  -> DplsApp (shared Compose)
```

The generated Apple framework is `DplsCore`.

## Fastest local workflow

From repository root:

```sh
bash tools/check_mobile.sh
```

The script validates `:wire`, `:runtime` and `:core`, lints/assembles Android and, on macOS, runs the Kotlin/Native suites plus the Xcode integration smoke test.

Direct JVM/Android commands:

```sh
cd mobile
./gradlew \
  :wire:testDebugUnitTest \
  :runtime:testDebugUnitTest \
  :core:testDebugUnitTest \
  :wire:lintDebug \
  :runtime:lintDebug \
  :core:lintDebug \
  :android:lintDebug \
  :android:assembleDebug
```

macOS/iOS:

```sh
./gradlew \
  :wire:iosSimulatorArm64Test \
  :runtime:iosSimulatorArm64Test \
  :core:iosSimulatorArm64Test \
  :core:linkDebugFrameworkIosSimulatorArm64

open ios/TestDPLS.xcodeproj
```

## Adding a feature

- frame/CRC/crypto/advertisement -> `wire/`;
- session lifecycle/identity/endpoint -> `runtime/`;
- screen/application flow/domain parser/journal -> `core/src/commonMain/`;
- Android Bluetooth/lifecycle quirk -> `core/src/androidMain/`;
- iOS CoreBluetooth/lifecycle quirk -> `core/src/iosMain/`;
- Android/Xcode product shell -> `android/` or `ios/`.

Do not add compatibility facades, duplicate controllers or speculative transport abstractions unless a real feature requires a new boundary.

## Concurrency

Production product state is serialized on the main event loop. Android GATT callbacks are delivered through the main `Handler`; iOS CoreBluetooth is created on the main queue.

Delayed work is additionally protected by identity tokens:

- protocol response -> `Frame.sequence`;
- operation timeout -> `(linkGeneration, sequence)`;
- session/reconnect/RSSI/connect timeout -> `linkGeneration`;
- scan timeout -> `scanGeneration`;
- journal timeout -> `logTimeoutGeneration`.

Cancellation is cleanup; sequence/generation checks are the correctness mechanism.

## BLE service

| Item | UUID |
|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` |
| RX / WRITE | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` |
| TX / INDICATE+NOTIFY | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` |

A successful GATT write is never treated as proof of hardware state. The shared application waits for the correlated firmware result and confirmed `STATE_REPORT`.