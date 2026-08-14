# Architecture

This document defines ownership boundaries in Test-DPLS. A change that moves safety or protocol responsibility across these boundaries should be treated as an architecture change, not a local refactor.

## Components

```text
┌─────────────────────────────────────────────────────────────┐
│ firmware/                                                   │
│                                                             │
│ portable C99 server ─────── PHY6252 HAL/GATT adapter        │
│ safety · persistence · outputs · ATT TX serialization       │
└───────────────────────────────┬─────────────────────────────┘
                                │ Test-DPLS binary protocol
┌───────────────────────────────▼─────────────────────────────┐
│ mobile/core/                                                │
│ protocol · domain · message contracts · session state       │
└───────────────────────┬───────────────────────┬─────────────┘
                        │                       │
              ┌─────────▼─────────┐   ┌────────▼──────────┐
              │ mobile/android/   │   │ mobile/ios/       │
              │ BluetoothGatt     │   │ CoreBluetooth     │
              │ Compose           │   │ SwiftUI           │
              └───────────────────┘   └───────────────────┘
```

## Ownership rules

### Firmware owns safety

Only firmware is allowed to decide whether an electrical test mode may remain energized. Mobile clients request actions; they never constitute the safety boundary.

Firmware owns:

- safe `NORMAL` state at boot/disconnect/error;
- break-before-make output switching;
- dangerous-mode timeout;
- session timeout;
- reserve and real-short safety overrides;
- persistent settings/authentication lock/event journal;
- physical ADC/readback and board mapping;
- ATT indication queue/confirmation timeout.

A mobile bug must not be able to keep a dangerous output active indefinitely.

### `mobile/core` owns cross-platform semantics

Code belongs in `mobile/core` when Android and iOS must produce the same answer from the same input.

Core currently owns:

- frame types and CRC-16/CCITT-FALSE;
- binary encode/decode;
- `STATE_REPORT`, device-info and journal parsing;
- shared domain types;
- session identifiers/tokens/nonces and reset semantics;
- deterministic session state transitions;
- Swift-facing codec bridge.

Core code must not import Android framework classes, CoreBluetooth, SwiftUI, Compose or global platform clocks.

### Platform clients own adapters and presentation

`mobile/android` owns Android-specific BLE scanning, permissions, bonding/GATT recovery, lifecycle and Compose presentation.

`mobile/ios` owns CoreBluetooth lifecycle, Apple platform integration and SwiftUI presentation.

Platform code may translate native events into core semantics, but it should not introduce a second framing/CRC/message parser implementation.

## Command truth model

A successful BLE write is not proof that hardware entered the requested mode.

The client state progression is:

```text
operator request
    → GATT write
    → firmware validates + applies/rejects
    → COMMAND_RESULT
    → STATE_REPORT
    → UI reflects confirmed hardware state
```

The final `STATE_REPORT` is the application-visible source of truth.

## Compatibility strategy

Firmware is C and the mobile core is Kotlin Multiplatform. They share a wire contract, not a source-language runtime.

Compatibility is protected by:

- firmware protocol tests;
- common CRC known-answer tests;
- all-message round-trip tests;
- randomized decoder tests;
- binary message contract tests;
- Kotlin/JVM and Kotlin/Native execution of the same core tests;
- native Android/iOS integration tests.

The PHY62XX SDK version is separately pinned in `firmware/sdk/phy6252-sdk.env`; updating it is not part of ordinary application refactoring.

## Dependency direction

Allowed:

```text
mobile/android → mobile/core
mobile/ios     → DplsCore framework generated from mobile/core
firmware       → vendor SDK through target adapter only
```

Not allowed:

```text
mobile/core → Android/iOS UI or Bluetooth APIs
portable firmware/src → vendor SDK headers
mobile platform code → a duplicate wire protocol implementation
```

## Testing policy

Coverage percentages are used only where they describe code that can be meaningfully unit-tested. Platform Bluetooth callback glue is not excluded from a broad `ble` percentage and then presented as if the entire subsystem were covered.

Quality gates instead combine:

- compiler warnings as errors for project code;
- firmware host tests + coverage + cppcheck;
- KMP common tests on JVM and Kotlin/Native;
- Android compatibility-facade coverage;
- Android lint/build;
- native iOS build/XCTest;
- hardware bring-up/E2E for physical behavior.

See [bring-up-checklist.md](bring-up-checklist.md) for hardware acceptance.
