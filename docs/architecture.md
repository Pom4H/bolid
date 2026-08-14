# Architecture

This document defines ownership boundaries in Test-DPLS. Moving a responsibility across one of these boundaries is an architecture change, not a local refactor.

## Runtime model

```text
┌──────────────────────────────────────────────────────────────┐
│ firmware/                                                    │
│ portable C99 server + PHY6252 adapter                        │
│ safety · persistence · outputs · ATT indication queue        │
└──────────────────────────────┬───────────────────────────────┘
                               │ Test-DPLS binary protocol
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ mobile/core/src/commonMain                                   │
│                                                              │
│ frame/CRC · auth contracts · message parsers                 │
│ domain/session state · deterministic rules · shared DplsApp  │
└───────────────────────┬───────────────────────┬──────────────┘
                        │                       │
               DplsController            DplsController
                        │                       │
              ┌─────────▼──────────┐   ┌────────▼────────────┐
              │ mobile/android/    │   │ core/src/iosMain/   │
              │ BluetoothGatt      │   │ CoreBluetooth       │
              │ service/permissions│   │ iOS controller      │
              │ MainActivity       │   │ ComposeUIViewCtrl   │
              └────────────────────┘   └────────┬────────────┘
                                                │
                                      ┌─────────▼──────────┐
                                      │ mobile/ios/        │
                                      │ Xcode shell/assets │
                                      │ tiny Swift bootstrap│
                                      └────────────────────┘
```

Both platforms render the same `DplsApp` from `commonMain`. Platform code supplies a `DplsController` and handles operating-system APIs; it does not own a second screen tree or protocol implementation.

## Ownership rules

### Firmware owns safety

Only firmware decides whether an electrical test mode may remain energized. Mobile software requests actions; it is never the safety boundary.

Firmware owns:

- safe `NORMAL` state at boot/disconnect/error;
- break-before-make output switching;
- dangerous-mode and session timeouts;
- reserve and real-short safety overrides;
- persistent settings/authentication lock/event journal;
- physical ADC/readback and board mapping;
- ATT indication serialization and confirmation timeout.

A mobile crash or stale connection must not be able to keep a dangerous output active indefinitely.

### `commonMain` owns shared application semantics

Put code in `mobile/core/src/commonMain` when Android and iOS must produce the same answer or show the same application behavior.

It owns:

- frame types and CRC-16/CCITT-FALSE;
- binary encode/decode;
- authentication byte contracts and known-answer crypto implementation;
- `AUTH_*`, `COMMAND_RESULT`, `SETTINGS_RESULT`, `STATE_REPORT`, device-info and journal parsing;
- shared domain types;
- session ids/tokens/nonces and reset semantics;
- deterministic session transitions;
- the Compose `DplsApp` screen tree.

`commonMain` must not import Android framework APIs, CoreBluetooth, UIKit or other platform-only APIs.

### Platform source owns operating-system adaptation

Android platform code owns:

- runtime Bluetooth permissions;
- bond/GATT recovery;
- foreground service/lifecycle integration;
- `BluetoothGatt` callbacks;
- Android application entry point.

iOS platform code in `core/src/iosMain` owns:

- `CBCentralManager` / `CBPeripheral` callbacks;
- secure random bytes from Apple Security;
- reconnect behavior tied to CoreBluetooth;
- the Compose `UIViewController` host.

`mobile/ios` itself is only the Xcode product shell: signing, plist, assets and the minimal Swift entry point required to launch the exported Kotlin view controller.

Platform adapters may translate native events into common state, but they must not introduce a duplicate frame codec, protocol parser, session model or application UI.

## Command truth model

A successful BLE write is not proof that hardware entered the requested mode.

```text
operator request
    → GATT write
    → firmware validates + applies/rejects
    → COMMAND_RESULT
    → STATE_REPORT
    → shared UI reflects confirmed hardware state
```

The final `STATE_REPORT` is the application-visible source of truth.

## Dependency direction

Allowed:

```text
mobile/android             → mobile/core/commonMain
mobile/core/iosMain        → mobile/core/commonMain
mobile/ios Xcode bootstrap → DplsCore framework
firmware target adapter    → pinned vendor SDK
```

Not allowed:

```text
commonMain → Android/CoreBluetooth/UIKit APIs
portable firmware/src → vendor SDK headers
platform code → duplicate protocol/domain/UI implementations
mobile app → hardware safety decisions
```

## Test placement

Put a test at the lowest layer that can prove the behavior once:

- firmware behavior → firmware host tests;
- wire bytes/CRC/auth/message parsing → KMP common tests;
- session transitions → KMP common tests;
- Android framework integration → Android tests/lint/build;
- iOS framework/export integration → Kotlin/Native tests + XCTest smoke;
- physical outputs/BLE pairing/radio behavior → hardware bring-up/E2E.

The common suite contains known-answer CRC and crypto vectors, all-message round trips, deterministic session tests, binary control/state/log contracts, 10,000 malformed decoder inputs and 2,000 randomized valid frame round trips.

Coverage percentages are only used where they describe unit-testable code honestly. Bluetooth callback glue is not excluded from a broad percentage and then presented as if the subsystem were covered.

## Developer experience rule

A developer adding an ordinary product feature should usually touch **one shared Kotlin area**, not Android + Swift copies.

- screen/presentation → `mobile/core/src/commonMain/.../app/`
- protocol contract → `mobile/core/src/commonMain/.../protocol/`
- deterministic state/session rule → `mobile/core/src/commonMain/.../session/`
- Android OS quirk → `mobile/android/`
- iOS OS quirk → `mobile/core/src/iosMain/`

Use `bash tools/check_mobile.sh` for the mobile loop and `bash tools/check_all.sh` for all host-side repository gates.

The PHY62XX SDK remains independently pinned in `firmware/sdk/phy6252-sdk.env`; changing the SDK is not part of ordinary application work.
