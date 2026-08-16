# Build, test and flash

This page is the developer execution guide. It separates host-side proof, platform builds and real-hardware validation.

## One-command checks

From repository root:

```sh
bash tools/check_mobile.sh
bash tools/check_all.sh
```

`check_mobile.sh` is the complete local mobile gate. `check_all.sh` adds repository/wire/architecture checks and firmware host validation.

On non-macOS hosts, iOS/Xcode checks are skipped because they require Apple tooling.

## Mobile modules

The current Gradle modules are:

```text
:wire
:runtime
:core
:android
```

### JVM / Android validation

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

The behavior-heavy shared tests are intentionally below the Android app shell.

### iOS / Kotlin Native

On macOS:

```sh
cd mobile

./gradlew \
  :wire:iosSimulatorArm64Test \
  :runtime:iosSimulatorArm64Test \
  :core:iosSimulatorArm64Test \
  :core:linkDebugFrameworkIosSimulatorArm64
```

Then run the Xcode integration smoke test:

```sh
xcodebuild test \
  -project mobile/ios/TestDPLS.xcodeproj \
  -scheme TestDPLS \
  -destination 'platform=iOS Simulator,id=<UDID>' \
  CODE_SIGNING_ALLOWED=NO
```

`tools/check_mobile.sh` discovers an available iPhone simulator automatically.

## Firmware host build

Portable firmware does not require the PHY6252 SDK:

```sh
cmake -S firmware -B firmware/build
cmake --build firmware/build
ctest --test-dir firmware/build --output-on-failure
```

Additional gates:

```sh
bash tools/lint_firmware.sh
bash tools/coverage_firmware.sh
```

Project-owned C code is built with warnings treated as errors. `cppcheck` covers project sources/tests. Coverage has an 80% line floor, but safety-critical behavior must also have explicit edge tests.

## Repository architecture/wire gates

Useful direct commands:

```sh
bash tools/check_repo_layout.sh
python3 tools/test_dpls_protocol_crc.py
python3 tools/architecture_guard.py
```

These cover different failure classes:

- repository layout: duplicate/legacy implementation drift;
- protocol CRC: language-neutral wire known answers;
- architecture guard: narrow project-specific one-owner/dependency invariants.

The architecture guard is not a complexity score and must not become one.

## PHY6252 target builds

The production SDK is fetched/pinned separately; see `firmware/sdk/phy6252-sdk.env`.

Target builds:

```sh
tools/build_firmware.sh keil tmp/test-dpls.hex
tools/build_firmware.sh gcc  tmp/test-dpls-gcc.hex
```

Both target paths consume the same project-owned firmware sources and board configuration:

- Keil/Arm Compiler 6 through CMSIS-Toolbox target files;
- GNU Arm Embedded through the target Makefile/linker script.

Do not mix ROM symbol maps, BLE/RF libraries or jump-table implementation from another SDK revision.

## Flashing

Normal flash:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex
```

Destructive full erase:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex --erase
```

`--erase` clears SNV, including settings/bonds/calibration/auth-lock/journal state. It is intentionally not the default.

For boot/UART capture:

```sh
python3 tools/serial_capture.py 20 --no-reset
```

## Hardware E2E

Host tests cannot prove electrical behavior, pairing/radio behavior or the power-stage characteristics.

Existing E2E helpers include:

```sh
python3 tools/e2e/phone_e2e_test.py
python3 tools/e2e/phone_e2e_test.py --journal-only
python3 tools/e2e/journal_reboot_check.py
```

Use [`../bring-up-checklist.md`](../bring-up-checklist.md) for the authoritative acceptance sequence on PB-03F-Kit and the power-stage prototype.

## CI mapping

GitHub Actions currently separates these concerns:

| Job | Purpose |
|---|---|
| repository contract | layout, language-neutral wire and architecture ownership |
| firmware host | tests, coverage and cppcheck |
| firmware pinout contract | revision-2 mapping/ADC scanning assertions |
| Android | `:wire/:runtime/:core` tests/lint + APK |
| iOS | K/N tests, DplsCore link and Xcode smoke test |

Local scripts should remain equivalent to the reusable CI gates. If a module is added or split, update both local scripts and CI in the same change.

## What a green build does not prove

Even all host/platform checks passing does not prove:

- actual output voltages/current/resistance;
- break-before-make on the real power stage;
- ADC accuracy across 5–27 V;
- reserve autonomy/current draw;
- real-short isolation timing;
- BLE behavior on every physical phone/PHY6252 pair.

Those require the hardware checklist and recorded test results.