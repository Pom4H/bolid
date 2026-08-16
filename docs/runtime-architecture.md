# Runtime architecture: one truth, serialized effects

An abstraction is useful here only when it removes mutable state, makes an invalid state unrepresentable, or defines a boundary that is used by the product today.

## Dependency zones

```text
:wire      frame/CRC/crypto/advertisement; no coroutines, UI or OS APIs
   ↓
:runtime   NodeId, BLE endpoint, session lifecycle and frame sequencing
   ↓
:core      product orchestration, journal, Compose and platform adapters
```

`core` may depend downward. `wire` and `runtime` must never depend on Compose, Android Bluetooth, CoreBluetooth or product screens.

The runtime module intentionally contains only concepts used by this PR. Mesh routing, serial links and passive observations are not pre-designed here; they belong to the PR that implements those features.

## Identity invariant

A device identity and its current BLE address are different facts.

- `NodeId` is the stable identity proven by `DEVICE_INFO`.
- `LinkEndpoint.Ble` is only the current route to the device.
- an advertised device id is a `candidateNodeId`: useful for locating cached credentials, but not authoritative.
- `DeviceSession.Online` requires a non-null verified `NodeId`.

Authentication therefore does not immediately mean `Online`:

```text
Connecting
    ↓
Discovering
    ↓
Linked
    ↓
Authenticating / Commissioning
    ↓
Synchronizing       authenticated, identity not proven yet
    ↓ DEVICE_INFO
Online              authenticated + verified NodeId
```

If the advertised candidate id and `DEVICE_INFO` disagree, the connection fails closed.

Legacy BLE-address credential keys remain readable for migration. Stable credentials are persisted by verified node id once identity has been proven.

## Request invariant — protocol v2

`DplsProtocol.Frame.sequence` is the **only** transaction id. `REQUEST/RESPONSE/EVENT/ERROR` flags describe correlation semantics independently from message type. A response or error echoes the request sequence.

The controller allows one transactional `Operation` at a time. Do not add `commandId`, `awaitingFoo`, `fooPending`, or a generic request broker until the product needs independent concurrent transactions.

A timeout carries the same operation sequence. Cancellation alone is not considered sufficient: if a canceled timeout has already become runnable, it may act only while its captured sequence is still the current operation sequence.

## Concurrency invariant

We avoid shared-state locking by serializing mutation rather than protecting arbitrary concurrent mutation.

### Product state

Production `DplsClient` runs on `Dispatchers.Main`.

- Android delivers GATT state and product callbacks on one main `Handler`.
- iOS creates `CBCentralManager` with the main queue.
- Compose actions and controller timers therefore observe the same serialized product state.

### Stale asynchronous work

Single-thread confinement removes data races, but it does not by itself remove stale logical work. Delayed work is therefore identity-checked:

- protocol response → `Frame.sequence`;
- operation timeout → captured operation `sequence`;
- connection/session loop/RSSI/reconnect → captured `linkGeneration`;
- scan deadline → captured `scanGeneration`;
- journal timeout → captured `logTimeoutGeneration`.

A late callback or timeout can run, but it cannot mutate a newer logical operation when its identity no longer matches.

This is deliberately preferred to a collection of independent mutexes and boolean locks.

## Session invariant — one truth

`DeviceSession` is the only mutable source of truth for link/auth lifecycle.

It owns:

- current endpoint;
- discovery identity hint while it is still untrusted;
- pre-auth client nonce;
- challenge `sessionId`, device nonce and auth salt;
- authenticated session id/token/salt;
- synchronization state before identity proof;
- verified stable `NodeId` in `Online`;
- recovering/failed lifecycle state.

`FrameSequencer` owns only the next protocol-v2 frame sequence. It must never grow authentication or lifecycle fields.

`DplsUiState.phase`, `authenticated`, `initialized` and `credentialsReady` are projections. `DplsClient` must never use those fields as protocol authority. Every UI mutation passes through `projectSession()`, which derives lifecycle presentation from `DeviceSession`.

```text
transport / decoded frame
          ↓
     DeviceSession        ← authoritative lifecycle/auth/identity
          ↓
       DplsClient
          ↓
     projectSession()
          ↓
      DplsUiState         ← presentation snapshot
          ↓
        Compose
```

The reverse dependency is forbidden.

## Journal invariant

`JournalMachine` owns journal paging/index state and returns explicit effects (`Ack`, `Pause`, `Complete`, `Error`). It does not know BLE, Compose, notifications or coroutine jobs.

Journal timeout ownership remains in `DplsClient`, and each timeout is generation-checked so an old page timeout cannot fail a newer load.

## Measurement invariant

Validity is encoded in the value:

- `null` — no valid measurement;
- `0` — valid zero volts.

Do not add `fooValue + fooValid` pairs. Capability bits stay packed in `DeviceCapabilities`; adding a firmware capability must not grow the `DeviceInfo` constructor.

## Firmware safety

`dpls_safety` is the single owner of dangerous-mode state, deadline math, revision and forced-return precedence. BLE, journal export, authentication proof and wall-clock time must not be added to it.

A failed `hal.hardware.apply_mode()` forces both physical outputs and the logical safety state to Normal, preventing physical/logical split-brain.

## Future mesh and RS-232

`NodeId` is already independent from a BLE address, which is the only prerequisite this PR needs for future routing.

Do not add `PacketRouter`, `ByteLink`, serial endpoints, passive observers or topology types until a real mesh/RS-232 feature consumes them. The first feature PR should introduce the smallest boundary justified by actual behavior.

## Architecture analysis

Do not optimize architecture against a home-grown numeric complexity score. Formatting must not be able to improve the reported architecture.

`tools/architecture_guard.py` is a narrow migration tripwire, not a complexity analyzer. It checks repository-specific invariants that are cheap and reliable to detect textually. The real protection comes from types, module dependencies and behavioral tests.

Generic cognitive/cyclomatic complexity, when useful, should come from AST-aware language tooling. It is diagnostic evidence, not an architecture score.

## Delete rules

Reject changes that introduce any of these without deleting equivalent state elsewhere:

- a second session/auth owner;
- a nullable identity inside `Online`;
- a second transaction id;
- controller decisions based on UI lifecycle projections;
- an unguarded delayed action that can outlive its operation/session;
- `awaitingX` / `xPending` orchestration booleans;
- UI strings in wire/domain enums;
- `value + valid` measurement pairs;
- a generic manager/repository/use-case interface with one implementation;
- speculative mesh/serial abstractions with no current caller.
