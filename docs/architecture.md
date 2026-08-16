# Architecture

This document is the repository-level ownership map for Test-DPLS. Detailed mobile runtime invariants live in `docs/runtime-architecture.md`.

The rule behind the layout is simple: **a test double may duplicate effects, not product decisions**. A responsibility may have several language-specific implementations only when they are independently checked against one explicit contract.

## Sources of truth

| Concern | Owner |
|---|---|
| DPLS frame/message/mode numeric contract | `protocol/dpls-wire.json` |
| Portable device behavior | `firmware/src` + `firmware/include` |
| Dangerous-mode safety | `firmware/src/dpls_safety.c` |
| Physical PHY6252 pins and ADC routing | `firmware/phy6252/dpls_board.h` |
| PHY6252 target adaptation | `firmware/phy6252` + `firmware/targets/phy6252` |
| PHY6252 ATT/OSAL/SNV host model | `firmware/phy6252_emu` |
| Host device test double | `firmware/sim` |
| Mobile frame/crypto/advertisement implementation | `mobile/wire` |
| Mobile identity/session lifecycle | `mobile/runtime` |
| Product orchestration and shared UI | `mobile/core` |
| Android/iOS OS adaptation | `mobile/core/src/androidMain`, `mobile/core/src/iosMain` |
| Interactive host lab | `tools/dpls-lab` + `mobile/web` |

## Firmware boundary

`firmware/src` is the portable product core. The same `dpls_server`, `dpls_safety`, LED, crypto, calibration and protocol implementation is linked into host tests and `dpls_simulator`.

Only firmware decides whether an electrical test mode may remain energized. Mobile software requests actions; it is not a safety boundary.

The PHY6252 target owns vendor-specific effects: GPIO, ADC, bonding, SNV, BLE/ATT scheduling and power-management quirks. Portable `firmware/src` must not include vendor SDK headers.

## Simulator boundary

`firmware/sim` is a **HAL/test-double zone around the real portable firmware**, not another implementation of the product.

It may own:

- deterministic/random hardware inputs;
- simulated GPIO/readback and voltage values;
- in-memory settings/event persistence;
- fault injection;
- snapshots and the stdio command surface;
- wiring to `phy6252_emu`.

It must not own:

- a DPLS message table or frame codec;
- HELLO/auth/session handlers;
- mode/safety state-machine decisions;
- a second journal protocol;
- a second product controller.

`dpls_simulator` must link the portable firmware core and `phy6252_emu`. `tools/architecture_guard.py` enforces this boundary by content, not by filenames.

### Test-double parity

A simulator is allowed to expose richer **diagnostic snapshots** than the product wire, because the lab needs observability. It must not expose richer behavior to `DplsClient` than the real target.

Example: the current PHY6252 scan response reserves an advertisement status byte but emits `0`. The simulator may still show `power`, `reserve_low` and `real_short` in its internal board snapshot, but soft-BLE and native simulated advertising must present status `0` until the physical target implements dynamic status too.

Mode/output and pin mappings are intentionally implemented independently by target, simulator and lab visualization. `tools/test_dpls_protocol_crc.py` compares all three against the canonical contract/board mapping so divergence fails CI instead of becoming a second truth.

## Mobile dependency direction

```text
:wire
  frame / CRC / crypto / advertisement
        ↓
:runtime
  NodeId / endpoint / session lifecycle / sequence
        ↓
:core
  DplsClient / product flow / journal / Compose
        ↓
platform adapters and product shells
```

`DeviceSession` is the only mutable owner of link/auth/identity lifecycle. `DplsProtocol.Frame.sequence` is the only transaction id. `STATE_REPORT` is the application-visible source of confirmed hardware state.

Platform adapters translate OS events into `DplsTransportListener`; they must not introduce another frame codec, parser, session controller or UI tree.

## Host lab boundary

`tools/dpls-lab` is orchestration and diagnostics. `mobile/web` runs the same Compose product UI through `LabBleTransport`.

The TypeScript lab may define its own WebSocket/control JSON (`spawn`, `snapshot`, `tick`, `ble_up`, etc.) because that is a lab API, not DPLS. It must not contain DPLS message ids, frame flags, crypto or another phone controller. `tools/architecture_guard.py` scans all TS/TSX files, so renaming a duplicate `protocol.ts` to `types.ts` cannot bypass the invariant.

## Wire contract

`protocol/dpls-wire.json` is the machine-readable description of the **current wire format**: frame sizes, flags, message ids, mode ids/output semantics and advertisement bits.

C, Kotlin and the dependency-free Python capture/replay codec remain separate implementations on purpose. CI compares each representation against the JSON contract and also runs independent known-answer/behavioral tests. We do not generate runtime code from JSON because that would make the implementations less independent and add a build dependency for little value at this stage.

The JSON field `wire_version: 2` is a byte-level protocol-format value. It is **not a product major version** and does not make the device/application a 2.x product line. Until the product is released to series, there is one active product line and one active wire contract.

## Test placement

Put a test at the lowest layer that proves the behavior once:

- portable firmware behavior → `firmware/tests`;
- PHY6252 queue/ATT behavior → `firmware/phy6252_emu` tests;
- C/Kotlin/Python wire agreement + target/simulator/lab mapping parity → `tools/test_dpls_protocol_crc.py`;
- application/session behavior → `DplsClient` common tests;
- product path against the real C simulator → soft-BLE/differential replay E2E;
- Android/iOS framework integration → platform tests/builds;
- physical GPIO/ADC/radio behavior → hardware bring-up/E2E.

## Repository gates

- `tools/check_repo_layout.sh` protects module/layout ownership.
- `tools/architecture_guard.py` protects lifecycle, dependency and simulator/lab boundaries.
- `tools/test_dpls_protocol_crc.py` protects machine-contract and target/simulator parity.
- `tools/check_all.sh` is the host-side aggregate gate.

Architecture changes should change the relevant gate in the same PR. A guard that no longer represents the current product is itself architecture drift.
