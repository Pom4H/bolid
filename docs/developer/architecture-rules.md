# Architecture rules

These rules exist to keep Test-DPLS readable under asynchronous BLE behavior and safe under hardware failures. A change that moves one of these responsibilities is an architecture change, not a local refactor.

## 1. One mutable owner per fact

Current ownership:

| Mutable fact | Owner |
|---|---|
| link/auth/verified identity lifecycle | `DeviceSession` |
| protocol frame sequence generation | `FrameSequencer` owned through `DplsWire` |
| current correlated product operation | `DplsClient.Operation` |
| active/staged credential verifier bytes | `DplsCredentials` |
| journal paging/index state | `JournalMachine` |
| shared presentation snapshot | `DplsUiState` |
| dangerous mode/deadline/revision | firmware `dpls_safety` |
| persisted settings/auth lock/journal | firmware HAL-backed stores |

Do not add equivalent mutable state elsewhere without deleting the old owner.

## 2. UI is a projection

`DplsUiState` exists for presentation. Lifecycle fields such as phase/authenticated/initialized/credentials-ready are derived through `projectSession()`.

Forbidden pattern:

```text
if (uiState.authenticated) {
    // decide protocol authority
}
```

Required pattern: make the decision from `DeviceSession`, credentials owner, current operation or another authoritative domain/runtime object, then project the result to UI.

## 3. Online identity is verified

`DeviceSession.Online` requires a non-null `NodeId` proven by authenticated `DEVICE_INFO`.

An advertised id is only a candidate consistency hint. A BLE address is only an endpoint. Authentication success does not imply identity proof, so the lifecycle contains `Synchronizing` between auth success and `Online`.

Do not make `Online.nodeId` nullable and do not silently fall back to advertisement/UI identity.

## 4. One protocol transaction id

Protocol v2 uses `Frame.sequence` as the only transaction id. Responses/errors echo it.

Do not add:

- `commandId` generation in v2;
- independent `requestId`/`operationId` on the wire;
- `awaitingX`/`xPending` booleans that duplicate the active operation;
- a generic request broker before the product needs independent concurrent transactions.

Legacy v1 command-id fields may exist only in decode compatibility/tests.

## 5. Serialized product state plus identity-checked async work

Product mutation is confined to the main/UI event loop. This removes data races but not stale logical work.

Every delayed/external result that can outlive its initiating work needs identity:

- response/error -> frame sequence;
- operation timeout -> link generation + frame sequence;
- reconnect/session/RSSI/connect timeout -> link generation;
- scan deadline -> scan generation;
- journal timeout -> journal timeout generation.

Cancellation is cleanup, not proof that stale work cannot run.

## 6. Dependency direction

Allowed mobile direction:

```text
:wire <- :runtime <- :core <- Android/iOS product shell
```

`wire` must not depend on coroutines, UI or OS Bluetooth APIs. `runtime` must not depend on Compose, platform Bluetooth or `core` product/domain code.

Platform adapters may depend on shared interfaces, but must not duplicate:

- frame codec;
- authentication model;
- application/session controller;
- product UI tree.

Portable firmware must not include vendor SDK headers. Vendor coupling belongs in the PHY6252 adapter/target layer.

## 7. Firmware owns safety

Mobile can request a mode; it cannot decide that hardware is safe to keep energized.

Firmware safety owns:

- safe `NORMAL` state;
- dangerous-mode deadline;
- authenticated-session timeout;
- reserve-low override;
- real-short/automatic-isolation override;
- break-before-make output behavior through hardware apply;
- fail-safe collapse to `NORMAL` when apply fails.

Do not move safety policy into mobile UI/controller code.

## 8. Measurement validity belongs in the value

In the shared domain model:

- `null` = no valid measurement;
- `0` = valid measured zero.

Do not create `fooValue + fooValid` state pairs. Capability bits stay packed in `DeviceCapabilities`; adding a capability should not force constructor growth throughout the product.

## 9. Do not pre-design unused transports

`NodeId` is intentionally independent from a BLE address so future routing is possible. That is enough for the current product.

Do not add speculative mesh/RS-232 abstractions (`PacketRouter`, generic byte links, passive observers, topology models, serial endpoints) until a feature actually consumes them.

## 10. Prefer types/tests over architecture scores

`tools/architecture_guard.py` is a narrow repository-specific tripwire. It verifies invariants that are cheap and reliable to detect textually.

Do not turn formatting, line counts or a home-grown numeric complexity score into an architecture target. Generic cyclomatic/cognitive complexity is diagnostic evidence only and should come from language-aware tooling when useful.

## Delete/reject rules

Reject a change that introduces any of these without a concrete requirement and removal of equivalent state:

- second session/auth owner;
- nullable identity in `Online`;
- second transaction id;
- controller decisions from UI lifecycle projection;
- unguarded delayed work that can outlive a link/operation;
- `awaitingX` / `xPending` orchestration booleans;
- UI strings in wire/runtime enums;
- `value + valid` measurement pairs;
- generic one-implementation Manager/Repository/UseCase wrappers;
- speculative transport/mesh abstractions.

## Review questions

For architecture-sensitive PRs, ask:

1. Which object owns every new mutable fact?
2. Can a late callback/timeout mutate a newer logical operation or link?
3. Is any UI projection being read as protocol authority?
4. Is identity trusted before authenticated `DEVICE_INFO`?
5. Does the change introduce a second request/command correlation id?
6. Can a mobile failure leave hardware energized indefinitely?
7. Does a new abstraction remove real state/coupling, or merely rename it?
8. Is the test placed at the lowest layer that can prove the behavior once?

The executable companion to these rules is `tools/architecture_guard.py`; the detailed rationale is also in [`../runtime-architecture.md`](../runtime-architecture.md).