# Test-DPLS

Firmware and mobile software for a BLE-controlled DPLS test device based on PHY6252 / PB-03F.

The repository has three ownership rules:

1. **firmware owns hardware safety**;
2. **Kotlin `commonMain` owns behavior shared by Android and iOS**;
3. **platform code only adapts OS APIs and application entry points**.

## Repository layout

| Path | Purpose |
|---|---|
| `firmware/` | Portable C99 server, PHY6252 HAL/GATT adapter and target builds |
| `mobile/core/` | Kotlin Multiplatform protocol, crypto contract, domain/session state and shared Compose UI |
| `mobile/android/` | Android BluetoothGatt/service/permission adapter and Activity shell |
| `mobile/ios/` | Minimal Xcode host: app metadata, assets and a tiny Swift bootstrap |
| `docs/` | Architecture, bring-up and PHY6252 engineering references |
| `tools/` | Build, flash, lint, coverage and one-command checks |
| `third_party/phy62x2/` | Vendored PHY62x2 utilities and reference material |

The production PHY62XX SDK is **not vendored**. Target builds fetch the SDK commit pinned as **3.1.2** in `firmware/sdk/phy6252-sdk.env`.

## Mobile architecture

There is one application UI and one cross-platform protocol/session implementation:

```text
                         Test-DPLS BLE wire contract
                                  │
                         ┌────────▼────────┐
                         │ mobile/core/    │
                         │ commonMain      │
                         │                │
                         │ protocol/CRC   │
                         │ auth contracts │
                         │ domain/session │
                         │ message parser │
                         │ DplsApp Compose│
                         └───────┬────────┘
                                 │
                  ┌──────────────┴──────────────┐
                  │                             │
         Android platform edge           iOS platform edge
         mobile/android/                 mobile/core/iosMain
         BluetoothGatt                   CoreBluetooth
         service/permissions             IosDplsController
         MainActivity                    IosBleTransport
                  │                             │
                  │                      mobile/ios/
                  │                      Xcode + ~bootstrap Swift
                  └──────── same DplsApp UI ────┘
```

`mobile/core/src/commonMain` contains no Android or Apple framework APIs. `iosMain` is intentionally inside the KMP module: it is the thin CoreBluetooth implementation behind the same `DplsController` contract that Android implements with `BluetoothGatt`.

Swift does not contain a second BLE client, protocol, crypto model or SwiftUI application. It only asks `DplsCore` for the Compose `UIViewController` required by the Xcode app target.

See [docs/architecture.md](docs/architecture.md) for the ownership rules.

## Safety model

The phone is never the safety boundary. Firmware owns the final electrical state.

- boot and disconnect force `NORMAL`;
- dangerous test modes have a hard timeout;
- session timeout returns the device to `NORMAL`;
- low reserve and real-short isolation override requested modes;
- output switching is break-before-make;
- authentication lock can persist across reconnect/reboot;
- ATT indications are serialized and advance only after confirmation.

A mobile crash, reconnect bug or stale UI state must not be able to keep a dangerous output active indefinitely.

## Developer quick start

Run the mobile checks from any directory:

```sh
bash tools/check_mobile.sh
```

Run all host-side repository checks:

```sh
bash tools/check_all.sh
```

On macOS, `check_mobile.sh` also runs the Kotlin/Native simulator tests and links the iOS framework. Hardware BLE behavior still requires a real iPhone/device pair.

### Firmware

```sh
cmake -S firmware -B firmware/build
cmake --build firmware/build
ctest --test-dir firmware/build --output-on-failure

# PHY6252 target
tools/build_firmware.sh keil tmp/test-dpls.hex
tools/build_firmware.sh gcc  tmp/test-dpls-gcc.hex
```

### Mobile

```sh
cd mobile
./gradlew :core:testDebugUnitTest
./gradlew :android:testDebugUnitTest :android:koverVerifyDebug
./gradlew :android:lintDebug :android:assembleDebug
```

On macOS:

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

## Test strategy

Behavior-heavy checks live at the lowest reusable layer:

- firmware host tests + coverage + cppcheck;
- CRC known-answer and all-message frame round trips;
- 10,000 randomized malformed decoder inputs;
- 2,000 randomized valid frame round trips;
- binary state/device-info/journal/control-message contracts;
- PBKDF2/HMAC/SHA-256 known-answer vectors;
- deterministic session safety/reset tests;
- the same KMP common tests on JVM and Kotlin/Native;
- Android lint/build and a small compatibility-facade coverage gate;
- native Xcode integration smoke test.

Platform Bluetooth callbacks are validated through platform builds/integration instead of hiding them behind a misleading coverage percentage.

## GATT

| Characteristic | UUID | Direction |
|---|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` | — |
| RX | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` | app → device (WRITE) |
| TX | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` | device → app (INDICATE/NOTIFY) |

Frame format: `version / type / flags / sequence / length / payload / CRC16-CCITT-FALSE`.

## Hardware revision 2

| Function | GPIO |
|---|---|
| ISO_1 / ISO_2 / ISO_T | P31 / P32 / P33 |
| KZ_1 / KZ_2 / KZ_T | P14 / P16 / P17 |
| ADC +1 / +2 / +Т / reserve | P20 / P15 / P24 / P23 |
| RGB R / G / B | P7 / P11 / P18 |
| Factory reset | P34 |

![PB-03F-Kit ↔ power-stage pinout](docs/hardware/pb03f-kit-power-pinout.png)

Logic is 3.3 V, active-high. All control outputs low is the safe `NORMAL` state.

## Hardware bring-up

Use [docs/bring-up-checklist.md](docs/bring-up-checklist.md) as the acceptance checklist. PHY6252-specific notes are collected in [docs/phy6252-programmer-reference.md](docs/phy6252-programmer-reference.md).

To flash a PB-03F-Kit:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex
```

`--erase` also clears SNV (settings, bonds and journal), so it is intentionally not the default.
