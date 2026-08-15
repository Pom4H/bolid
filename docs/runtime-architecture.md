# Runtime architecture: complexity tree-shaking

An abstraction is useful here only when it removes mutable state or makes an invalid state unrepresentable.

## Dependency zones

```text
:wire      frame/CRC/crypto/advertisement; no coroutines, UI or OS APIs
   ↓
:runtime   NodeId, endpoint/link, session and routing primitives
   ↓
:core      product domain, controller, journal reducer, Compose and platform adapters
```

`core` may depend downward. `wire` and `runtime` must never depend on Compose, Android Bluetooth, CoreBluetooth or product screens.

## Identity invariant

A node and a route are different facts.

- `NodeId` is stable device identity and owns credentials/journal history.
- `LinkEndpoint` is how this process currently reaches a node: BLE address, serial port, or a routed mesh destination.
- reconnecting through another gateway must not create another logical device.

Legacy BLE-address credential keys are read only for migration. New code keys secrets by node id.

## Link invariant

Runtime sees a `ByteLink`, not Bluetooth callbacks. GATT write queues, bonding, MTU negotiation, UART framing and radio-specific errors belong below that boundary. Discovery is independent from an established link.

The current Android/iOS BLE adapter is intentionally migrated last: product code already targets the runtime vocabulary, while platform adapters may temporarily retain `DplsTransport` during the branch transition.

## Request invariant — protocol v2

`DplsProtocol.Frame.sequence` is the **only** transaction id. `REQUEST/RESPONSE/EVENT/ERROR` flags describe correlation semantics independently from message type. A response or error echoes the request sequence.

The controller intentionally allows one transactional `Operation` at a time. Do not add `commandId`, `awaitingFoo`, `fooPending`, or a generic request broker until the product actually needs independent concurrent transactions.

Journal paging is separately serialized by `JournalMachine`; it does not require a request map.

## Session invariant

`DeviceSession` is a sum type. Runtime code must not represent states such as `READY && !authenticated`. Link-scoped work is cancelled as a group instead of maintaining a list of resettable feature flags.

## Journal invariant

`JournalMachine` is a reducer. It owns paging/index state and returns effects (`Ack`, `Pause`, `Complete`, `Error`). It does not know BLE, Compose, clocks, notifications or coroutine jobs.

## Measurement invariant

Validity is encoded in the value:

- `null` — no valid measurement;
- `0` — valid zero volts.

Do not add `fooValue + fooValid` pairs. Capability bits stay packed in `DeviceCapabilities`; adding a firmware capability must not grow the `DeviceInfo` constructor.

## Mesh

Mesh is a network layer, not another `DplsTransport`. A `RoutedPacket` wraps an unchanged end-device DPLS frame and identifies source/destination nodes. The same node session can therefore be reached directly or through a gateway without changing auth/control/journal code.

Neighbor RSSI samples are observations. Topology estimation consumes them outside the control session.

## RS-232

A writable serial connection may implement `ByteLink`. A passive tap implements `ObservationSource`; it cannot authenticate or control a device by type, so read-only capture never grows fake control methods.

## Firmware safety

`dpls_safety` is the single owner of dangerous-mode state, deadline math, revision and forced-return precedence. BLE, journal export, authentication proof and wall-clock time must not be added to it.

## Delete rules

Reject changes that introduce any of these without deleting equivalent state elsewhere:

- a second transaction id;
- `awaitingX` / `xPending` booleans;
- transport-type branches above the link/router boundary;
- UI strings in wire/domain enums;
- `value + valid` measurement pairs;
- a generic manager/repository/use-case interface with one implementation;
- a mesh or serial API that pretends every channel supports control.
