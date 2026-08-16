# Test-DPLS developer handbook

This directory is the entry point for developers working on Test-DPLS. It documents the behavior that is hard to reconstruct safely from one source file: ownership, lifecycle, wire contracts, safety precedence, persistence, hardware mapping and validation.

## Start here

| If you are changing… | Read first |
|---|---|
| overall architecture or module boundaries | [System overview](system-overview.md) and [Architecture rules](architecture-rules.md) |
| BLE/session/reconnect behavior | [Mobile runtime](mobile-runtime.md) |
| binary protocol, authentication or message payloads | [Protocol v2](protocol-v2.md) |
| firmware state machine, HAL or safety logic | [Firmware runtime](firmware-runtime.md) |
| GPIO, ADC, outputs, LED or factory reset | [Hardware revision 2](hardware-rev2.md) |
| build, tests, flashing or CI | [Build, test and flash](build-test-flash.md) |
| hardware acceptance | [Bring-up checklist](../bring-up-checklist.md) |
| PHY6252/SDK/ROM/linker details | [PHY6252 programmer reference](../phy6252-programmer-reference.md) |
| clock and journal timestamp behavior | [Timekeeping](../timekeeping.md) |
| four-channel live voltage requirements | [Live voltage requirements](../live-voltage-requirements.md) |

## The four ownership rules

1. **Firmware owns hardware safety.** Mobile requests actions; firmware decides whether an electrical mode may remain energized.
2. **`DeviceSession` owns mobile link/auth/identity lifecycle.** UI fields are projections, not protocol authority.
3. **Protocol v2 has one transaction id: `Frame.sequence`.** Do not add a second command/request identifier.
4. **Platform code adapts OS APIs.** Product behavior belongs in shared Kotlin unless an Android/iOS API forces a platform boundary.

## Repository map

```text
firmware/
  src/ + include/        portable C99 protocol/server/safety/calibration/LED
  phy6252/               PHY6252 HAL, GATT, ADC, persistence, board mapping
  targets/phy6252/       Keil/AC6 and GNU Arm target builds

mobile/
  wire/                   framing, CRC, crypto, advertisement parsing
  runtime/                stable identity, endpoint and DeviceSession lifecycle
  core/                   DplsClient, domain parsers, journal, shared Compose UI,
                          Android/iOS transport adapters
  android/                Android application shell
  ios/                    Xcode product shell and minimal Swift bootstrap

docs/                     engineering references and acceptance material
tools/                    repository checks, builds, flashing, E2E helpers
third_party/               vendored PHY62x2 utilities/reference material
```

Dependency direction is intentionally one-way:

```text
mobile:  :wire  <-  :runtime  <-  :core  <-  :android / iOS host

firmware: portable server/safety  <-  PHY6252 adapter  <-  pinned vendor SDK
```

The current detailed runtime invariants are also documented in [`../runtime-architecture.md`](../runtime-architecture.md).

## Sources of truth

Do not turn documentation copies into a second authority. When values can be encoded in code, the code remains authoritative.

| Fact | Source of truth |
|---|---|
| wire frame version/types/flags | `mobile/wire/.../DplsProtocol.kt`, `firmware/include/dpls_protocol.h` |
| mobile lifecycle/auth/verified identity | `mobile/runtime/.../DeviceSession.kt` |
| mobile transaction sequencing | `mobile/runtime/.../DplsSession.kt` + `DplsWire` in `mobile/core` |
| product orchestration | `mobile/core/.../DplsClient.kt` |
| hardware safety state/deadlines | `firmware/include/dpls_safety.h`, `firmware/src/dpls_safety.c` |
| firmware protocol state machine | `firmware/include/dpls_server.h`, `firmware/src/dpls_server.c` |
| rev2 pin assignment | `firmware/phy6252/dpls_board.h` |
| production PHY62XX SDK revision | `firmware/sdk/phy6252-sdk.env` |
| acceptance status | `docs/bring-up-checklist.md` plus real hardware results |

## Definition of a safe change

A change is not complete only because it compiles. At minimum, prove it at the lowest reusable layer:

- wire bytes/crypto/parser behavior -> `:wire` common tests;
- lifecycle/session types -> `:runtime` tests;
- product behavior -> `:core` fake-transport tests;
- firmware logic -> host C tests/lint/coverage;
- Android/iOS API behavior -> platform build/integration tests;
- electrical behavior -> real hardware bring-up/E2E.

Use [`build-test-flash.md`](build-test-flash.md) for the current commands.