# Mobile runtime

The mobile application has one product controller (`DplsClient`) and one authoritative lifecycle model (`DeviceSession`). Android and iOS deliver platform events into the same shared controller and render the same shared Compose UI.

## Lifecycle state machine

```text
Offline
  |
  | select device
  v
Connecting
  |
  | GATT connected
  v
Discovering
  |
  | service + RX/TX + CCCD ready
  v
Linked
  |
  | HELLO
  v
+-------------------------+
| AUTH_CHALLENGE received |
+-------------------------+
      | initialized=false             | initialized=true
      v                               v
Commissioning                   Authenticating
      | SETUP                         | AUTH_PROOF
      +---------------+---------------+
                      |
                      | successful AUTH_RESULT
                      v
                Synchronizing
                      |
                      | STATE_REPORT + DEVICE_INFO
                      | authenticated identity check
                      v
                    Online
```

Recovery is explicit:

```text
Online / authenticated work
          |
          | Bluetooth loss / recoverable transport failure
          v
       Recovering
          |
          | reconnect + rebuild link/auth state
          +-------------------------------> normal lifecycle
```

Failures that cannot be recovered enter `DeviceSession.Failed`. A normal disconnect returns to `Offline`.

## What each state means

- `Offline`: no active route.
- `Connecting`: a BLE endpoint was selected; connection is not usable yet.
- `Discovering`: physical link exists; service/subscription negotiation is still in progress.
- `Linked`: transport is usable and the client nonce for HELLO/auth is owned by the session.
- `Commissioning`: device is not initialized; the active challenge belongs to first-time setup.
- `Authenticating`: initialized device; challenge-response is in progress.
- `Synchronizing`: authentication succeeded, but stable device identity is not yet proven.
- `Online`: authenticated session plus verified non-null `NodeId`.
- `Recovering`: route is known, but link/auth state must be rebuilt.
- `Failed`: the current attempt ended with a typed link/platform/protocol failure.

`DplsUiState.phase`, `authenticated`, `initialized` and `credentialsReady` are projections of this state. `DplsClient` must not read them back as lifecycle authority.

## Identity and credential trust

The discovery advertisement may include a device id. The client stores it as `candidateNodeId` only.

Credential behavior is intentionally asymmetric:

1. before identity proof, BLE-address migration keys may be used to recover existing credentials;
2. after authenticated `DEVICE_INFO` proves a `NodeId`, credentials are persisted under the stable node key as well;
3. if advertised candidate id and authenticated `DEVICE_INFO` disagree, the client fails closed.

A BLE address is therefore a route, not an identity.

## Transaction model

`DplsClient` permits one transactional `Operation` at a time for operations that require a correlated response. Current operation types include mode changes, device-info reads, time sync, histogram reads and settings changes.

Every operation owns the exact protocol-v2 `Frame.sequence` returned by `DplsWire.request(...)`. A response only completes the operation when its sequence matches.

Do not add `commandId`, `awaitingFoo`, `fooPending` or another request broker unless the product gains a real requirement for independent concurrent transactions.

## Serialized mutation

Production `DplsClient` uses `Dispatchers.Main`.

- Android `AndroidBleTransport` serializes mutable GATT state and listener callbacks through the main `Handler`.
- iOS creates CoreBluetooth on the main queue.
- Compose actions, transport callbacks and controller jobs therefore observe one serialized product state.

This prevents data races, but it does not prevent stale logical work. Delayed work must still prove that it belongs to the current attempt.

## Stale-work guards

The current guards are:

| Work/result | Identity check |
|---|---|
| protocol response/error | `Frame.sequence` |
| operation timeout | `(linkGeneration, operation.sequence)` |
| reconnect/session loop/RSSI/connect timeout | `linkGeneration` |
| scan timeout | `scanGeneration` |
| journal timeout | `logTimeoutGeneration` |

Cancellation is cleanup, not the correctness mechanism. A cancelled coroutine may already be runnable; generation/sequence checks make that late execution harmless.

## Normal online loop

Once authenticated, the controller starts a shared session loop.

- state refresh period: 1 second;
- telemetry becomes stale after 3 seconds without a fresh `STATE_REPORT`;
- keep-alive is used when a state request should not be sent;
- journal transfer pauses the normal session loop and resumes it when paging finishes;
- time sync is attempted after identity is verified.

The exact constants live in `DplsClient.kt` and should remain the code source of truth.

## Journal ownership

`JournalMachine` owns journal paging/index state. It returns explicit effects such as ACK, pause, complete and error. It does not own BLE, coroutine jobs, UI or notifications.

`DplsClient` owns the timeout around those effects. A journal timeout is generation-checked so an old page timeout cannot fail a newer load.

## Platform boundary

Shared code talks to two narrow interfaces:

- `DplsTransport`: scan/connect/reconnect/send/RSSI/disconnect plus transport events;
- `DplsPlatformServices`: clock, secure random, preferences/credentials, local date formatting, Bluetooth settings, keep-alive service and operator notifications.

Platform adapters must not introduce a second protocol codec, session controller or application UI.

## Where to change code

- wire frame/CRC/crypto/advertisement -> `mobile/wire`;
- session lifecycle/identity/endpoint -> `mobile/runtime`;
- product orchestration, parsers, journal, shared UI -> `mobile/core/src/commonMain`;
- Android GATT/bond/CCCD quirks -> `mobile/core/src/androidMain`;
- iOS CoreBluetooth lifecycle -> `mobile/core/src/iosMain`;
- Android/Xcode product shells -> `mobile/android`, `mobile/ios`.

See [Protocol v2](protocol-v2.md) for frame correlation and [Architecture rules](architecture-rules.md) for forbidden duplicate state.