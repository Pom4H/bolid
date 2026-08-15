# Runtime architecture: complexity tree-shaking

This branch treats an abstraction as useful only when it removes mutable state or makes an invalid state unrepresentable.

## Dependency zones

```text
:wire      frame/CRC/crypto/advertisement; no coroutines, UI or OS APIs
   ↓
:runtime   NodeId, endpoint/link, request correlation, session/routing primitives
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

Runtime sees a `ByteLink`, not Bluetooth callbacks. GATT write queues, bonding, MTU negotiation, UART framing and radio-specific errors belong below that boundary. Discovery is a separate capability from an established link.

## Request invariant

`DplsProtocol.Frame.sequence` is the transaction id. `REQUEST/RESPONSE/EVENT/ERROR` flags describe correlation semantics independently from message type. `RequestBroker` is the only request correlation table; new features must not add `awaitingFoo`, `fooPending` or a second command id.

The deployed v1 command payload remains readable while firmware/app migrate together. New protocol work must converge on sequence correlation rather than adding another id.

## Session invariant

`DeviceSession` is a sum type. Runtime code must not represent states such as `READY && !authenticated`. Link-scoped work must be cancelled with the link scope instead of maintaining a list of resettable timeout flags.

## Journal invariant

`JournalMachine` is a pure reducer. It owns paging/index state and returns effects (`Ack`, `Pause`, `Complete`, `Error`). It does not know BLE, Compose, clocks, notifications or coroutine jobs.

## Measurement invariant

Validity is encoded in the value:

- `null` — no valid measurement;
- `0` — valid zero volts.

Do not add `fooValue + fooValid` pairs. Capability bits stay packed in `DeviceCapabilities`; adding a firmware capability must not grow the `DeviceInfo` constructor.

## Mesh

Mesh is a network layer, not another `DplsTransport`. A `RoutedPacket` wraps an unchanged end-device payload and identifies source/destination nodes. The same device session can therefore be reached directly or through a gateway without changing auth/control/journal code.

Neighbor RSSI samples are observations. Topology estimation consumes them outside the control session.

## RS-232

A writable serial connection may implement `ByteLink`. A passive tap implements `ObservationSource`; it cannot authenticate or control a device by type, so read-only capture never grows fake control methods.

## Firmware safety

`dpls_safety` is a pure state machine for dangerous-mode invariants. It owns mode deadline math and the precedence of disconnect/session timeout/reserve/real-short returns. BLE, journal export, authentication proof and wall-clock time must not be added to it.

## Delete rules

Reject changes that introduce any of these without deleting equivalent state elsewhere:

- a second transaction id;
- `awaitingX` / `xPending` booleans;
- transport-type branches above the link/router boundary;
- UI strings in wire/domain enums;
- `value + valid` measurement pairs;
- a generic manager/repository/use-case interface with one implementation;
- a mesh or serial API that pretends every channel supports control.
