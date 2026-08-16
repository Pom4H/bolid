# System overview

Test-DPLS consists of three runtime layers that meet at explicit contracts: the mobile application, the BLE binary protocol and the firmware/hardware boundary.

## End-to-end model

```text
operator
   |
   v
shared Compose UI
   |
   v
DplsController / DplsClient
   |
   +---------------- product state ----------------+
   |                                               |
   v                                               v
DeviceSession                                  JournalMachine
(link/auth/id)                                 (log paging)
   |
   v
DplsWire -> protocol-v2 frame -> DplsTransport
   |                              |
   |                     AndroidBleTransport / IosBleTransport
   |                              |
   +---------------- BLE GATT ----+
                                  |
                                  v
                         PHY6252 GATT adapter
                                  |
                                  v
                           dpls_server
                         /     |      \
                        /      |       \
                    auth    journal   dpls_safety
                                      |
                                      v
                                hardware HAL
                                      |
                         GPIO / ADC / reserve / LED
```

The phone is not the safety boundary. A successful GATT write means only that bytes were accepted by the platform stack. The application-visible hardware truth comes from firmware responses and state reports, while the physical safety truth remains in firmware.

## Mobile dependency zones

The KMP project is split into three shared modules:

```text
:wire
  frame, CRC, endian helpers, crypto, advertisement parsing
    ^
    |
:runtime
  NodeId, LinkEndpoint, DeviceSession, FrameSequencer
    ^
    |
:core
  DplsClient, domain models/parsers, journal, Compose, platform adapters
```

`wire` and `runtime` are deliberately product-light. They must not depend on Compose, Android Bluetooth or CoreBluetooth. `core` may depend downward on both.

Platform shells stay thin:

- `mobile/android/` owns Android application entry, permissions and debug E2E plumbing;
- `mobile/ios/` owns the Xcode product shell, signing/plist/assets and the minimal Swift bootstrap;
- actual Android/iOS BLE adapters live in `mobile/core/src/androidMain` and `mobile/core/src/iosMain`.

## Firmware zones

Portable firmware lives in `firmware/src` + `firmware/include` and can be host-tested without the vendor SDK.

- `dpls_protocol` owns frame encode/decode and CRC;
- `dpls_server` owns protocol/session/settings/journal orchestration;
- `dpls_safety` owns dangerous-mode state, deadlines and forced-return precedence;
- `dpls_led` owns indication scenes;
- `dpls_calib` owns calibration math.

`firmware/phy6252` adapts the portable core to the actual target: GATT, GPIO, ADC, persistence, BLE identity and board mapping.

## Command truth

A mode request crosses several boundaries before it is real:

```text
operator confirms mode
        |
        v
DplsClient sends MODE_SET request(sequence=N)
        |
        v
platform writes BLE characteristic
        |
        v
firmware validates auth + safety + hardware apply
        |
        +---- rejected -> ERROR / COMMAND_RESULT
        |
        v
COMMAND_RESULT(sequence=N)
        |
        v
STATE_GET / STATE_REPORT
        |
        v
shared UI renders confirmed device state
```

`Frame.sequence` is the correlation key. The final `STATE_REPORT` is the product-visible state snapshot; neither a UI pending state nor a successful BLE write is sufficient proof.

## Identity model

Three values that may look similar have different trust levels:

- BLE address: current route only;
- advertised device id: `candidateNodeId`, useful as a consistency hint but not authoritative;
- authenticated `DEVICE_INFO.deviceId`: verified stable `NodeId`.

`DeviceSession.Online` requires a verified non-null `NodeId`. Authentication success therefore enters `Synchronizing` first; the client becomes `Online` only after authenticated device information proves identity.

## Safety model

Firmware forces safe behavior independently of mobile state. The important invariants are:

- boot/disconnect return outputs to `NORMAL`;
- dangerous modes have a hard deadline;
- authenticated-session inactivity returns to `NORMAL`;
- low reserve and real-short isolation override requested modes;
- output switching is break-before-make;
- failure to apply a requested hardware mode collapses logical and physical state back to `NORMAL`.

See [Firmware runtime](firmware-runtime.md) for execution details and [Architecture rules](architecture-rules.md) for invariants that changes must preserve.

## Physical revision

The current production mapping documented by the project is hardware revision 2. Pin assignments are compile-time checked in `firmware/phy6252/dpls_board.h`. Do not treat copied tables in documentation as a replacement for that file.

See [Hardware revision 2](hardware-rev2.md) and the [bring-up checklist](../bring-up-checklist.md).