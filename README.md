# Test-DPLS

Firmware and mobile software for a BLE-controlled DPLS test device based on PHY6252 / PB-03F.

The repository is organized around one rule: **hardware safety stays in firmware; protocol and session semantics stay in the shared mobile core; platform code only adapts operating-system APIs.**

## Repository layout

| Path | Purpose |
|---|---|
| `firmware/` | Portable C99 server, PHY6252 HAL/GATT adapter and target builds |
| `mobile/core/` | Kotlin Multiplatform protocol, domain model, wire parsing and session state |
| `mobile/android/` | Android BluetoothGatt adapter and Compose UI |
| `mobile/ios/` | iOS CoreBluetooth adapter and SwiftUI host |
| `docs/` | Bring-up, hardware and PHY6252 engineering notes |
| `tools/` | Build, coverage, lint, flashing, UART and E2E utilities |
| `third_party/phy62x2/` | Vendored PHY62x2 flashing utilities |

The complete PHY62XX SDK is **not vendored**. Builds fetch the pinned SDK 3.1.2 commit declared in `firmware/sdk/phy6252-sdk.env`.

## Safety model

The phone is never the safety boundary. Firmware always owns the final state of the outputs.

- boot and disconnect force `NORMAL`;
- test modes have a hard timeout;
- session timeout returns the device to `NORMAL`;
- low reserve and real-short isolation override a requested test mode;
- only one mode output can be active at a time (break-before-make);
- authentication lock survives reconnects and can persist through reboot;
- secrets and session tokens are cleared on link reset;
- ATT indications are serialized by the PHY6252 TX queue and advance only after confirmation.

## Mobile architecture

`mobile/core` is the source of truth for code that must behave identically on Android and iOS:

```text
firmware (C)
      │
      │ Test-DPLS binary protocol
      ▼
mobile/core (Kotlin Multiplatform)
  protocol · domain · session
      │                 │
      ▼                 ▼
Android adapter      iOS adapter
BluetoothGatt        CoreBluetooth
Compose              SwiftUI
```

Android keeps a thin compatibility facade for existing package names; CRC, framing, message parsing and domain types delegate to `mobile/core`. The core is built for Android/JVM, iOS arm64 and iOS Simulator arm64.

## Build and test

### Portable firmware

```sh
cmake -S firmware -B firmware/build
cmake --build firmware/build
ctest --test-dir firmware/build --output-on-failure
bash tools/lint_firmware.sh
bash tools/coverage_firmware.sh
```

### PHY6252 target

```sh
tools/build_firmware.sh keil tmp/test-dpls.hex
tools/build_firmware.sh gcc  tmp/test-dpls-gcc.hex
```

The target is checked with both Keil MDK / Arm Compiler 6 and GNU Arm Embedded. Project-owned code treats warnings as errors; vendor SDK warnings are isolated from that policy.

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

The KMP protocol suite includes known-answer CRC checks, round-trip tests, binary message contracts, deterministic session-safety tests and randomized decoder/round-trip tests. Android Kover covers the Android compatibility facade; platform Bluetooth framework glue is validated by builds and integration tests rather than hidden behind an artificial coverage percentage.

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
