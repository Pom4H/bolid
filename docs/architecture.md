# Architecture

This document defines the top-level ownership boundaries in Test-DPLS. For task-oriented developer documentation start at [`developer/README.md`](developer/README.md). Detailed one-owner/runtime invariants live in [`runtime-architecture.md`](runtime-architecture.md).

Moving a responsibility across one of these boundaries is an architecture change, not a local refactor.

## Runtime model

```text
mobile

┌─────────────────────────────────────────────────────────────┐
│ :wire                                                       │
│ frame · CRC · crypto · advertisement parsing                │
└───────────────────────────────▲─────────────────────────────┘
                                │
┌───────────────────────────────┴─────────────────────────────┐
│ :runtime                                                    │
│ NodeId · LinkEndpoint · DeviceSession · FrameSequencer      │
└───────────────────────────────▲─────────────────────────────┘
                                │
┌───────────────────────────────┴─────────────────────────────┐
│ :core                                                       │
│ DplsClient · domain/parsers · journal · shared Compose      │
│ AndroidBleTransport / IosBleTransport platform adapters     │
└───────────────────────────────┬─────────────────────────────┘
                                │ Test-DPLS protocol v2
                                ▼
┌─────────────────────────────────────────────────────────────┐
│ firmware portable C99 core                                  │
│ protocol · server · safety · persistence contracts          │
└───────────────────────────────┬─────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────┐
│ PHY6252 adapter                                              │
│ GATT · GPIO · ADC · SNV · power · LED · board mapping       │
└─────────────────────────────────────────────────────────────┘
```

Android and iOS use the same `DplsClient` and shared Compose UI. Platform-specific code adapts Bluetooth/lifecycle/storage/notification APIs; it must not contain another product controller, protocol implementation or UI tree.

## Ownership rules

### Firmware owns hardware safety

Only firmware decides whether an electrical test mode may remain energized. Mobile requests actions; it is never the safety boundary.

Firmware owns:

- safe `NORMAL` state at boot/disconnect/error;
- break-before-make output switching;
- dangerous-mode and authenticated-session timeouts;
- reserve-low and real-short safety overrides;
- persistent settings/authentication lock/event journal;
- physical ADC/readback and board mapping;
- ATT indication serialization and confirmation timeout.

A mobile crash or stale connection must not keep a dangerous output active indefinitely.

### `DeviceSession` owns mobile lifecycle

`mobile/runtime` owns link/auth/identity lifecycle through `DeviceSession`.

- BLE address is a route (`LinkEndpoint.Ble`), not stable identity;
- advertisement device id is an untrusted `candidateNodeId` consistency hint;
- authentication success enters `Synchronizing`;
- `Online` requires an authenticated, verified, non-null `NodeId` from `DEVICE_INFO`.

The controller must not store independent copies of session id/token/nonces/auth salt or use UI lifecycle projections as protocol authority.

### `:wire` owns the binary framing contract

`mobile/wire` owns protocol-v2 frame types, CRC, endian helpers, crypto and advertisement parsing. It has no coroutine/UI/OS dependency.

Protocol v2 uses exactly one transaction id: `Frame.sequence`. Responses/errors echo the request sequence. Legacy v1 `commandId` fields remain decode compatibility only and must not re-enter v2 orchestration.

### `:core` owns product behavior

`DplsClient` coordinates product behavior: scan/connect/auth, reconnect, commands, settings, state refresh, time sync, journal and operator-facing state.

`DplsUiState` is presentation. Lifecycle fields are projected from `DeviceSession`; controller decisions do not read them back as authority.

`JournalMachine` owns journal paging state. `DplsCredentials` owns active/staged verifier bytes and persistence/zeroization.

### Platform code owns OS adaptation

Android adapter code owns `BluetoothGatt`, bonding, MTU, CCCD, write queue/retries, stale-bond handling, foreground keep-alive and Android platform services.

iOS adapter code owns CoreBluetooth lifecycle/write queue, Apple clock/random/storage/alerts and Compose hosting.

`mobile/android` and `mobile/ios` remain product shells (Activity/permissions/E2E and Xcode/signing/plist/assets/bootstrap respectively).

## Command truth model

A successful BLE write is not proof that hardware entered the requested mode.

```text
operator request
    -> DplsClient sends MODE_SET with Frame.sequence=N
    -> platform transport writes GATT
    -> firmware validates auth + safety and applies/rejects hardware
    -> COMMAND_RESULT response echoes sequence=N
    -> STATE_REPORT provides the product-visible hardware snapshot
    -> shared UI reflects confirmed state
```

A result with a stale sequence cannot complete a newer operation.

## Concurrency model

Product state is serialized on the main event loop:

- `DplsClient` runs on `Dispatchers.Main`;
- Android GATT state/callbacks are confined to the main `Handler`;
- iOS CoreBluetooth is created on the main queue.

Serialization removes data races, not stale logical work. Delayed/external work is identity-checked:

- protocol result -> `Frame.sequence`;
- operation timeout -> `(linkGeneration, sequence)`;
- reconnect/session/RSSI/connect timeout -> `linkGeneration`;
- scan timeout -> `scanGeneration`;
- journal timeout -> `logTimeoutGeneration`.

Cancellation is cleanup; identity checks are the correctness mechanism.

## Dependency direction

Allowed:

```text
:core -> :runtime -> :wire
Android/iOS product shell -> :core
platform transport implementations -> shared DplsTransport contract
firmware PHY6252 adapter -> portable firmware core + pinned vendor SDK
```

Not allowed:

```text
:wire/:runtime -> Compose or OS Bluetooth APIs
:runtime -> :core product/domain code
platform code -> duplicate protocol/controller/UI implementation
portable firmware/src -> vendor SDK headers
mobile app -> hardware safety authority
```

## Test placement

Put a test at the lowest reusable layer that proves the behavior once:

- frame/CRC/crypto -> `:wire` common tests;
- lifecycle/identity -> `:runtime` tests;
- application/session behavior -> `:core` fake-transport tests;
- firmware protocol/safety behavior -> firmware host tests;
- Android/iOS framework integration -> platform build/integration tests;
- physical outputs, ADC accuracy, pairing/radio/current -> hardware bring-up/E2E.

`tools/architecture_guard.py` is a narrow migration/invariant tripwire. Types, module dependencies and behavioral tests remain the primary architecture protection.

## Developer experience rule

An ordinary product feature should normally touch one shared Kotlin area rather than parallel Android + Swift implementations.

- wire contract -> `mobile/wire/`;
- lifecycle/identity -> `mobile/runtime/`;
- product/UI/parsers/journal -> `mobile/core/src/commonMain/`;
- Android OS/BLE quirk -> `mobile/core/src/androidMain/`;
- iOS OS/BLE quirk -> `mobile/core/src/iosMain/`;
- hardware safety/server rule -> portable firmware;
- PHY6252-specific integration -> `firmware/phy6252/`.

Use `bash tools/check_mobile.sh` for the mobile loop and `bash tools/check_all.sh` for all host-side repository gates. Changing the pinned PHY62XX SDK is a separate target migration, not ordinary application work.