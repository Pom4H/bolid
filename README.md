# Test-DPLS

Firmware and mobile software for a BLE-controlled DPLS test device based on PHY6252 / PB-03F.

The repository follows four ownership rules:

1. **firmware owns hardware safety**;
2. **`DeviceSession` owns mobile link/auth/verified-identity lifecycle**;
3. **protocol v2 uses `Frame.sequence` as its only transaction id**;
4. **shared Kotlin owns product behavior; platform code adapts OS APIs**.

For developer documentation start at [`docs/developer/README.md`](docs/developer/README.md).

## Repository layout

| Path | Purpose |
|---|---|
| `firmware/` | Portable C99 protocol/server/safety core, PHY6252 HAL/GATT adapter and target builds |
| `mobile/wire/` | KMP frame/CRC/crypto/advertisement contract |
| `mobile/runtime/` | KMP stable identity, endpoint and `DeviceSession` lifecycle |
| `mobile/core/` | `DplsClient`, domain/parsers, journal, shared Compose UI and Android/iOS adapters |
| `mobile/android/` | Android application shell, permissions and debug E2E |
| `mobile/ios/` | Xcode product shell and minimal Swift bootstrap |
| `docs/` | Developer handbook, architecture, bring-up and PHY6252 references |
| `tools/` | Build, flash, lint, coverage, architecture and E2E checks |
| `third_party/phy62x2/` | Vendored PHY62x2 utilities/reference material |

The production PHY62XX SDK is not vendored. Target builds fetch the commit pinned as **3.1.2** in `firmware/sdk/phy6252-sdk.env`.

## Architecture

```text
mobile

:wire
  frame / CRC / crypto / advertisement
    ^
    |
:runtime
  NodeId / endpoint / DeviceSession / sequence
    ^
    |
:core
  DplsClient / domain / journal / Compose / platform adapters
    ^
    |
Android shell / iOS Xcode host

                       BLE protocol v2
                              |
                              v
firmware portable server -> dpls_safety -> PHY6252 hardware HAL
                              |
                    GPIO / ADC / reserve / outputs
```

A BLE address is a route, not device identity. An advertised device id is an untrusted candidate. `DeviceSession.Online` is possible only after authenticated `DEVICE_INFO` proves a stable non-null `NodeId`.

See [`docs/developer/system-overview.md`](docs/developer/system-overview.md) and [`docs/runtime-architecture.md`](docs/runtime-architecture.md).

## Safety model

The phone is never the safety boundary. Firmware owns the final electrical state.

- boot and disconnect force `NORMAL`;
- dangerous test modes have a hard timeout;
- authenticated-session inactivity returns outputs to `NORMAL`;
- low reserve and real-short isolation override requested modes;
- output switching is break-before-make;
- hardware apply failure collapses physical and logical state to `NORMAL`;
- authentication lock can persist across reconnect/reboot;
- ATT indications are serialized and advance only after confirmation.

A mobile crash, reconnect bug or stale UI state must not keep a dangerous output active indefinitely.

## Developer quick start

Run the complete mobile loop:

```sh
bash tools/check_mobile.sh
```

Run all host-side repository gates:

```sh
bash tools/check_all.sh
```

`check_all.sh` includes repository layout, language-neutral wire CRC, architecture ownership, firmware host coverage/lint and the mobile checks.

### Firmware

```sh
cmake -S firmware -B firmware/build
cmake --build firmware/build
ctest --test-dir firmware/build --output-on-failure

bash tools/lint_firmware.sh
bash tools/coverage_firmware.sh

# PHY6252 target
tools/build_firmware.sh keil tmp/test-dpls.hex
tools/build_firmware.sh gcc  tmp/test-dpls-gcc.hex
```

### Mobile

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

On macOS:

```sh
cd mobile
./gradlew \
  :wire:iosSimulatorArm64Test \
  :runtime:iosSimulatorArm64Test \
  :core:iosSimulatorArm64Test \
  :core:linkDebugFrameworkIosSimulatorArm64
```

See [`docs/developer/build-test-flash.md`](docs/developer/build-test-flash.md) for target flashing and Xcode commands.

## Where to make changes

- frame/CRC/crypto/advertisement -> `mobile/wire/`;
- lifecycle/identity/endpoint -> `mobile/runtime/`;
- product orchestration, parsers, journal or shared UI -> `mobile/core/src/commonMain/`;
- Android BLE/lifecycle quirk -> `mobile/core/src/androidMain/`;
- iOS CoreBluetooth/lifecycle quirk -> `mobile/core/src/iosMain/`;
- protocol/server/safety behavior -> portable `firmware/src` + `firmware/include`;
- PHY6252 GPIO/ADC/GATT/SNV integration -> `firmware/phy6252/`;
- Xcode signing/assets/capabilities -> `mobile/ios/`.

An ordinary product feature should normally change shared Kotlin once, not add parallel Android and Swift product implementations.

## Protocol v2

GATT service:

| Characteristic | UUID | Direction |
|---|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` | — |
| RX | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` | app -> device, WRITE |
| TX | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` | device -> app, INDICATE/NOTIFY |

Frame format:

```text
version / type / flags / sequence / length / payload / CRC16-CCITT-FALSE
```

`sequence` is the only v2 transaction id. A successful BLE write is not proof that hardware changed state; correlated firmware response and subsequent `STATE_REPORT` are required.

Full contract: [`docs/developer/protocol-v2.md`](docs/developer/protocol-v2.md).

## Hardware revision 2

| Function | GPIO |
|---|---|
| ISO_1 / ISO_2 / ISO_T | P31 / P32 / P33 |
| KZ_1 / KZ_2 / KZ_T | P14 / P16 / P17 |
| ADC +1 / +2 / +T / reserve | P20 / P15 / P24 / P23 |
| RGB R / G / B | P07 / P11 / P18 |
| Factory reset | P34 |

Logic is 3.3 V active-high; all control outputs low is safe `NORMAL`.

Source of truth: `firmware/phy6252/dpls_board.h`. Developer map: [`docs/developer/hardware-rev2.md`](docs/developer/hardware-rev2.md).

## Hardware bring-up

Use [`docs/bring-up-checklist.md`](docs/bring-up-checklist.md) for real-device acceptance and [`docs/phy6252-programmer-reference.md`](docs/phy6252-programmer-reference.md) for SDK/ROM/linker details.

To flash without clearing SNV:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex
```

`--erase` also clears SNV (settings, bonds, calibration, auth lock and journal), so it is intentionally destructive.