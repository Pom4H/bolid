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
│ DplsClient — single application controller                   │
│ frame/CRC · auth/crypto · parsers · domain/session           │
│ reconnect · commands · settings · journal · shared DplsApp   │
└──────────────────────────────┬───────────────────────────────┘
                               │ DplsTransport + platform services
                 ┌─────────────┴─────────────┐
                 │                           │
        ┌────────▼──────────┐       ┌────────▼──────────┐
        │ mobile/android/   │       │ core/src/iosMain/ │
        │ AndroidBleTransport│      │ IosBleTransport  │
        │ service/permissions│      │ Apple clock/RNG  │
        │ MainActivity      │       │ Compose UI host  │
        └───────────────────┘       └────────┬──────────┘
                                             │
                                    ┌────────▼──────────┐
                                    │ mobile/ios/       │
                                    │ Xcode shell/assets│
                                    │ one Swift bootstrap│
                                    └───────────────────┘
```

Both platforms use the same `DplsClient` and render the same `DplsApp`. Platform code only supplies BLE transport, clock/random services and OS lifecycle integration.

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

### `commonMain` owns product behavior

Put code in `mobile/core/src/commonMain` when Android and iOS must produce the same answer or show the same product behavior.

It owns:

- `DplsClient`, the single application/session controller;
- frame types and CRC-16/CCITT-FALSE;
- binary encode/decode;
- authentication byte contracts plus SHA-256/HMAC/PBKDF2 implementation;
- `AUTH_*`, `COMMAND_RESULT`, `SETTINGS_RESULT`, `STATE_REPORT`, device-info and journal parsing;
- shared domain types and secret-bearing session runtime;
- reconnect/keepalive/state-refresh/command/settings/journal orchestration;
- command-id correlation so stale acknowledgements cannot complete a newer command;
- the Compose `DplsApp` screen tree.

`commonMain` must not import Android framework APIs, CoreBluetooth, UIKit or other platform-only APIs.

### Platform code owns operating-system adaptation

Android platform code owns:

- runtime Bluetooth permissions;
- `BluetoothGatt` callbacks, bonding, MTU and CCCD subscription;
- transient write retries and stale-bond/GATT-133 recovery;
- foreground service/lifecycle integration;
- Android application entry point and debug E2E broadcast driver.

iOS platform code in `core/src/iosMain` owns:

- `CBCentralManager` / `CBPeripheral` callbacks;
- write queue and CoreBluetooth lifecycle;
- secure random bytes and clock from Apple frameworks;
- the Compose `UIViewController` host.

`mobile/ios` itself is only the Xcode product shell: signing, plist, assets and the one Swift entry point required to launch the exported Kotlin view controller.

Platform adapters translate native events into `DplsTransportListener` events. They must not introduce a second frame codec, parser, session controller or application UI.

## Command truth model

A successful BLE write is not proof that hardware entered the requested mode.

```text
operator request
    → DplsClient assigns command id
    → platform transport writes GATT
    → firmware validates + applies/rejects
    → matching COMMAND_RESULT
    → STATE_REPORT
    → shared UI reflects confirmed hardware state
```

A `COMMAND_RESULT` with a different command id is ignored. The final `STATE_REPORT` is the application-visible source of hardware truth.

## Dependency direction

Allowed:

```text
mobile/android transport/shell → mobile/core/commonMain
mobile/core/iosMain transport  → mobile/core/commonMain
mobile/ios Xcode bootstrap     → DplsCore framework
firmware target adapter        → pinned vendor SDK
```

Not allowed:

```text
commonMain → Android/CoreBluetooth/UIKit APIs
portable firmware/src → vendor SDK headers
platform code → duplicate protocol/controller/UI implementations
mobile app → hardware safety decisions
```

## Test placement

Put a test at the lowest layer that can prove the behavior once:

- firmware behavior → firmware host tests;
- wire bytes/CRC/auth/message parsing → KMP common tests;
- application/session behavior → `DplsClient` fake-transport common tests;
- Android framework integration → Android lint/build + hardware E2E;
- iOS framework/export integration → Kotlin/Native tests + XCTest smoke;
- physical outputs/BLE pairing/radio behavior → hardware bring-up/E2E.

The common suite includes CRC and crypto known-answer vectors, all-message round trips, binary control/state/log contracts, session reset tests, shared-controller flow tests, stale command-id rejection, 10,000 malformed decoder inputs and 2,000 randomized valid frame round trips.

## Repository invariants

`tools/check_repo_layout.sh` prevents the architecture from drifting back into duplicate implementations. It rejects legacy top-level directories as well as old Android/Swift controllers, duplicate platform protocol facades, duplicate UI trees and obsolete KMP bridges.

Production iOS intentionally contains one Swift bootstrap source. The Android BLE package intentionally contains one transport implementation.

## Developer experience rule

An ordinary product feature should normally touch **one shared Kotlin area**, not Android + Swift copies.

- screen or application flow → `mobile/core/src/commonMain/.../app/`
- protocol/auth contract → `mobile/core/src/commonMain/.../protocol/`
- secret/session runtime → `mobile/core/src/commonMain/.../session/`
- Android OS/Bluetooth quirk → `mobile/android/`
- iOS OS/Bluetooth quirk → `mobile/core/src/iosMain/`

Use `bash tools/check_mobile.sh` for the mobile loop and `bash tools/check_all.sh` for all host-side repository gates.

The PHY62XX SDK remains independently pinned in `firmware/sdk/phy6252-sdk.env`; changing the SDK is not part of ordinary application work.
