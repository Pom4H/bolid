# Test-DPLS

Firmware and mobile software for a BLE-controlled DPLS test device based on PHY6252 / PB-03F.

The repository follows three ownership rules:

1. **firmware owns hardware safety**;
2. **Kotlin `commonMain` owns the product behavior shared by Android and iOS**;
3. **platform code only adapts operating-system APIs and application entry points**.

## Repository layout

| Path | Purpose |
|---|---|
| `firmware/` | Portable C99 server, PHY6252 HAL/GATT adapter and target builds |
| `firmware/zmu/` | Cortex-M0 E2E of the **portable** board (`sim/`), not phy6252-emu |
| `mobile/core/` | Kotlin Multiplatform `DplsClient`, protocol/crypto/domain/session code and shared Compose UI |
| `mobile/android/` | Android shell: permissions, Activity, debug E2E (version **1.4.1**) |
| `mobile/ios/` | Minimal Xcode host: metadata, assets and one tiny Swift bootstrap (version **1.4.1**) |
| `mobile/web/` | Compose wasm phone (`LabBleTransport` over WebSocket) |
| `docs/` | Architecture, bring-up and PHY6252 engineering references |
| `tools/` | Build, flash, lint, coverage, lab and one-command checks |
| `tools/dpls-lab/` | Hub: `dpls_lab.sh` starts `dpls_simulator` + wasm phone |
| `third_party/phy62x2/` | Vendored PHY62x2 utilities and reference material |
| `third_party/phy6252-emu/` | Guest PHY6252 hex emulator ([Pom4H/phy6252-emu](https://github.com/Pom4H/phy6252-emu)) |

The production PHY62XX SDK is **not vendored**. Target builds fetch the SDK commit pinned as **3.1.2** in `firmware/sdk/phy6252-sdk.env`.

## Mobile architecture

There is one application controller, one UI and one protocol implementation:

```text
                    Test-DPLS BLE wire contract
                              │
                    ┌─────────▼──────────┐
                    │ mobile/core/       │
                    │ commonMain         │
                    │                    │
                    │ DplsClient         │
                    │ protocol + crypto  │
                    │ domain + session   │
                    │ message parsers    │
                    │ DplsApp Compose UI │
                    └─────────┬──────────┘
                              │ DplsTransport
                 ┌────────────┴────────────┐
                 │                         │
        Android platform edge              iOS platform edge
        core/androidMain                  core/iosMain
        AndroidBleTransport               IosBleTransport
        AndroidPlatformServices           IosPlatformServices
                 │                                 │
        mobile/android/                    mobile/ios/
        Activity + permissions             Xcode + one Swift bootstrap
                 └────── same DplsClient + DplsApp ──────┘
```

`commonMain` contains no Android or Apple framework APIs. Android and iOS implement only the `DplsTransport`/platform-services boundary. Swift does not contain a second BLE client, protocol, crypto model or SwiftUI application.

See [docs/architecture.md](docs/architecture.md) for the detailed ownership rules.

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

Run the complete mobile loop from the repository root:

```sh
bash tools/check_mobile.sh
```

Run all host-side repository checks:

```sh
bash tools/check_all.sh
```

On macOS, `check_mobile.sh` also runs Kotlin/Native simulator tests, links `DplsCore` and executes the Xcode integration smoke test. Physical BLE behavior still requires a real phone/device pair.

### Device-free firmware ↔ mobile E2E (zmu)

Run the portable firmware core on a Cortex-M0 emulator and verify the responses with Kotlin wire encoders — no PB-03F or phone required:

```sh
# needs: rustc/cargo, arm-none-eabi-gcc, JDK 17+
bash tools/fetch_zmu.sh          # clones https://github.com/jjkt/zmu and builds zmu-cortex-m0
bash tools/zmu_run_all.sh tmp/zmu/target/release/zmu-cortex-m0
# covers: all firmware/tests/* on Cortex-M0 + phone-E2E protocol matrix (modes/settings/journal)
```

### Soft-BLE product stack (host simulator)

Exercise real `DplsClient` against host `dpls_simulator` over a stdio “soft-BLE” transport — no phone and no PHY6252 radio:

```sh
# needs: cmake, JDK 17+
bash tools/soft_ble_e2e.sh
# covers: connect/auth, all test modes, rename, journal, identify LED, real-short reject, low reserve
```

This is not a substitute for the Chinese board’s BLE/ADC/SNV stack; it covers the shared product protocol path on the host.

### Host wasm phone (simulator + laptop BLE)

```sh
bash tools/dpls_lab.sh
# http://127.0.0.1:8787
```

Same Compose `DplsApp` as Android/iOS (`mobile/web`). Details: [tools/dpls-lab/README.md](tools/dpls-lab/README.md).

The chip hex runner is a git submodule (`third_party/phy6252-emu`); Bolid does not launch it. See [docs/chip-emulator.md](docs/chip-emulator.md).

Current PHY6252 firmware is **1.4.2**; Android/iOS remain **1.4.1**. Protocol framing remains v2. BLE scan uses the project Service UUID and `Test-DPLS-XXXX`; full serial, firmware version and user-assigned name are read from `DEVICE_INFO_REPORT` after connection.

### Capture real phone↔board sessions (for simulator fidelity)

Record live logcat (BLE frames + client `STATE`/`E2E` markers) and optionally UART, then parse/replay into `dpls_simulator`:

```sh
python3 tools/session_capture/record_session.py --name lab
python3 tools/session_capture/parse_session.py tmp/sessions/session-*-lab.log
python3 tools/session_capture/test_session_capture.py   # offline smoke
```

See `tools/session_capture/README.md`.

### Firmware

```sh
cmake -S firmware -B firmware/build
cmake --build firmware/build
ctest --test-dir firmware/build --output-on-failure
# phy6252_emu is the reusable PHY6252 ATT/OSAL host model; DPLS sim/zmu sit on top of it.

# PHY6252 target
tools/build_firmware.sh keil tmp/test-dpls.hex
tools/build_firmware.sh gcc  tmp/test-dpls-gcc.hex
```

### Mobile

```sh
cd mobile
./gradlew :core:testDebugUnitTest
./gradlew :core:lintDebug :android:lintDebug :android:assembleDebug
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

## Where to make changes

- Shared screen/presentation behavior → `mobile/core/src/commonMain/.../app/`.
- Protocol, auth or binary contract → `mobile/core/src/commonMain/.../protocol/` plus `commonTest` byte-contract tests.
- Session secret/runtime rules → `mobile/core/src/commonMain/.../session/`.
- Android Bluetooth/lifecycle quirk → `mobile/android/`.
- iOS CoreBluetooth/lifecycle quirk → `mobile/core/src/iosMain/`.
- Xcode signing/assets/capabilities → `mobile/ios/`.

An ordinary product feature should normally require **one shared Kotlin change**, not parallel Android + Swift implementations.

## Test strategy

Behavior-heavy checks live at the lowest reusable layer:

- firmware host tests + coverage + cppcheck;
- zmu Cortex-M0 E2E (Kotlin request vectors → ARM firmware server → Kotlin response checks);
- CRC known-answer and all-message frame round trips;
- 10,000 randomized malformed decoder inputs;
- 2,000 randomized valid frame round trips;
- binary auth/command/state/device-info/journal contracts;
- PBKDF2/HMAC/SHA-256 known-answer vectors;
- shared `DplsClient` fake-transport tests including stale command-id rejection and reconnect safety;
- the same KMP common tests on JVM and Kotlin/Native;
- Android adapter lint/build;
- native Xcode/KMP integration smoke test.

The repository layout check also rejects reintroducing duplicate platform controllers, UI trees or protocol facades.

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

A current board must be provisioned before it is expected to advertise:

```sh
python3 tools/make_factory_identity.py \
  --serial 12874 \
  --binary-output tmp/factory-00012874.bin \
  --metadata tmp/factory-00012874.json

tools/flash_firmware.sh tmp/test-dpls.hex
tools/flash_factory_identity.sh tmp/factory-00012874.bin
```

The application image and factory identity are separate. `--erase` is guarded because a full chip erase destroys factory identity as well as SNV.
