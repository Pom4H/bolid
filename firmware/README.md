# Test-DPLS firmware

PHY6252 firmware for the Test-DPLS device, version **1.4.2** (`DPLS_FW_VERSION_*` in `include/dpls_server.h`). The code is split into a portable C99 server and a narrow PHY6252 adapter so protocol/safety logic can be tested on the host without the vendor SDK.

## Layout

| Path | Responsibility |
|---|---|
| `src/` + `include/` | Portable protocol, server, LED, HMAC and calibration |
| `phy6252_emu/` | Reusable PHY6252 ATT/OSAL/SNV host model (no DPLS types). Copy into other PHY6252 projects |
| `sim/` | DPLS board on top of `phy6252_emu`: HMAC verify, LED scenes, lab voltages |
| `zmu/` | Cortex-M0 image that runs that board model under [zmu](https://github.com/jjkt/zmu). Product E2E — not `third_party/phy6252-emu`. |
| `tests/` | Host-side behavioral and edge-case tests |
| `phy6252/` | HAL/GATT adapter, ADC, persistence and board mapping |
| `targets/phy6252/` | Keil CMSIS solution and GNU Arm target build |
| `sdk/phy6252-sdk.env` | Immutable SDK source/commit pin |

The complete PHY62XX SDK is fetched into `sdk/PHY62XX_SDK_3.1.2/` and is not committed to this repository.

## Safety invariants

Firmware is the safety boundary; the mobile client cannot bypass these rules.

- Startup and BLE disconnect force `NORMAL`.
- Dangerous test modes expire automatically.
- Session timeout returns outputs to `NORMAL`.
- Low reserve and real-short isolation outrank a requested test mode.
- Output switching is break-before-make and only one test mode may be energized.
- Authentication attempt limits survive reconnects; persistent lock data is stored in SNV.
- Session tokens/nonces are cleared when a link is reset.
- TX is one PDU in flight. Indicate-only waits for ATT confirmation (2 s fail-safe). Samsung CCCD 0x03 uses `GATT_Notification` and advances on the 80 ms notify-pace tick. That chip behaviour lives in `phy6252_emu/`; the DPLS host/zmu board calls it instead of duplicating queues.
- Proof verification is HMAC-SHA256 of the session transcript against the stored verifier (factory E2E password `TestDpls01` on the simulator).
- A valid factory identity is mandatory before BLE advertising: no runtime MAC generation and no fallback identity from SNV.

## Host build, tests and static analysis

From the repository root:

```sh
cmake -S firmware -B firmware/build
cmake --build firmware/build
ctest --test-dir firmware/build --output-on-failure
bash tools/lint_firmware.sh
bash tools/coverage_firmware.sh
```

Device-free Cortex-M0 E2E against the Kotlin wire codec (via [zmu](https://github.com/jjkt/zmu)):

```sh
bash tools/fetch_zmu.sh
bash tools/zmu_run_all.sh tmp/zmu/target/release/zmu-cortex-m0
```

This runs every `firmware/tests/*` binary on the emulator plus the phone-E2E protocol
matrix (identify/auth, all five modes, keep-alive, name/password, journal chunk).

Soft-BLE against the same host `dpls_simulator` (real `DplsClient`, no radio):

```sh
bash tools/soft_ble_e2e.sh
```

Project-owned C code is compiled with warnings treated as errors. `cppcheck` covers `src/`, `include/`, `sim/`, `phy6252_emu/` and `tests/`. Host line coverage has an 80% floor; safety behavior is additionally exercised by explicit edge-case tests rather than relying on the percentage alone.

## PHY6252 target builds

```sh
tools/build_firmware.sh keil tmp/test-dpls.hex
tools/build_firmware.sh gcc  tmp/test-dpls-gcc.hex
```

- Keil: `targets/phy6252/test-dpls.csolution.yml` + Arm Compiler 6 + `scatter_load.sct`.
- GCC: `targets/phy6252/Makefile` + `phy6252.ld`.
- Both targets use the same project sources and board configuration.
- Vendor SDK warnings are isolated from the project warning policy; `tools/build_firmware.sh` rejects warnings emitted by the project build.

`fromelf` writes an entry-point record into each output region. The build script keeps the entry point only in `ER_IROM1`, because the PHY62x2 flashing utility stops parsing at the first such record.

## GATT and protocol

| Item | UUID |
|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` |
| RX / WRITE | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` |
| TX / INDICATE+NOTIFY | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` |

Frame format: `version / type / flags / sequence / length / payload / CRC16-CCITT-FALSE`.

Application authentication is challenge-response. Five failed attempts lock authentication for 300 seconds; persistent lock state uses SNV record `0x84`.

BLE discovery uses the project 128-bit Service UUID and `Test-DPLS-XXXX` local name. Firmware version, full serial number, hardware revision, capabilities and the user-assigned name are returned by `DEVICE_INFO_REPORT` after connection; no Bluetooth SIG Manufacturer Specific Data is used.

## Revision 2 board mapping

Source of truth: `phy6252/dpls_board.h`.

| Function | GPIO |
|---|---|
| ISO_1 / ISO_2 / ISO_T | P31 / P32 / P33 |
| KZ_1 / KZ_2 / KZ_T | P14 / P16 / P17 |
| ADC +1 / +2 / +Т / reserve | P20 / P15 / P24 / P23 |
| RGB R / G / B | P7 / P11 / P18 |
| Factory reset | P34 |

Logic is 3.3 V active-high; all control outputs low is `NORMAL`.

Mode mapping:

- `OPEN_T` → `ISO_T`
- `OPEN_MAIN` → `ISO_2`
- `SHORT_1` / `SHORT_2` / `SHORT_T` → `KZ_1` / `KZ_2` / `KZ_T`

## Persistence

SNV allocation:

| Range / record | Data |
|---|---|
| `0x20..0x5F` | BLE bonds |
| `0x80` | settings |
| `0x81` | initialization marker |
| `0x83` | ADC calibration |
| `0x84` | authentication lock |
| `0x90..0xA3` | event journal |

The SNV filesystem uses three sectors at `0x1103C000..0x1103EFFF`. Factory identity occupies the separate sector `0x1103F000..0x1103FFFF` and is provisioned with `tools/flash_factory_identity.sh`. Application XIP ends before SNV, so linker growth cannot overwrite persistent data.

## PHY6252 integration notes

The target compensates for several SDK-specific behaviors in `targets/phy6252/source/dplsBLEPeripheral.c`:

- SRAM0+SRAM1+SRAM2 must remain retained during sleep to avoid a wake/reset loop;
- the filesystem must be mounted with `hal_fs_init(0x1103C000, 3)` before SNV use;
- watchdog feeding occurs from the IRQ path, so watchdog behavior must not be interpreted as a task-liveness proof;
- public BLE address application is retried after `GAPROLE_STARTED`; advertising stays disabled until the identity is confirmed.

Sleep is blocked only while a dangerous mode is energized. In `NORMAL`, the MCU can sleep and the LED timer does not run unnecessarily.

Floating-point ADC calibration stays in XIP so the constrained `ER_IROM1` region is reserved for code that must execute there.

## Hardware acceptance

PB-03F-Kit bring-up covers boot, BLE, commissioning, authentication, test modes, journal and four ADC channels. Final power-stage acceptance additionally requires calibration, current behavior, automatic short isolation and the P34 factory-reset path.

Use [the bring-up checklist](../docs/bring-up-checklist.md) for acceptance. PHY6252-specific findings are collected in [the programmer reference](../docs/phy6252-programmer-reference.md).
