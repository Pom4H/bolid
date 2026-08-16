# Test-DPLS protocol v2

Protocol v2 is the binary application contract between the mobile client and firmware. The BLE GATT service transports complete protocol frames; GATT write success is not an application acknowledgement.

## GATT service

| Item | UUID | Direction |
|---|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` | — |
| RX | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` | app -> device, WRITE |
| TX | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` | device -> app, INDICATE/NOTIFY |

Firmware serializes ATT indications and advances the queue only after confirmation. If the firmware RX queue is full, the GATT layer returns an ATT resource error instead of silently dropping the frame.

## Frame layout

All multi-byte integer fields are little-endian.

```text
offset  size  field
0       1     version = 2
1       1     message type
2       1     flags
3       2     sequence
5       2     payload length
7       N     payload
7+N     2     CRC16-CCITT-FALSE
```

Frame overhead is 9 bytes. Firmware currently caps the payload at 235 bytes so a complete frame fits the negotiated BLE transport contract.

### Flags

| Bit | Name | Meaning |
|---:|---|---|
| 0 | `REQUEST` | frame initiates a correlated request |
| 1 | `RESPONSE` | frame responds to a request |
| 2 | `EVENT` | unsolicited event semantics |
| 3 | `ERROR` | error response semantics |

Message type and correlation semantics are independent. A response or error echoes the request `sequence`.

## One transaction id

`Frame.sequence` is the only transaction id in protocol v2.

```text
request:  MODE_SET, sequence=41
                 |
                 v
              firmware
                 |
                 v
response: COMMAND_RESULT, sequence=41
```

Do not add a second `commandId`. The mobile parser retains read-only support for legacy v1 command/settings result payloads, but v2 generation and runtime correlation use the frame sequence only.

## Message catalog

| Type | Value | Typical semantics |
|---|---:|---|
| `HELLO` | `0x01` | begin application authentication |
| `AUTH_CHALLENGE` | `0x02` | device challenge + salt + initialization state |
| `AUTH_PROOF` | `0x03` | client proof |
| `AUTH_RESULT` | `0x04` | authentication/setup result and session token |
| `SETUP` | `0x05` | first-time name/password setup |
| `DEVICE_INFO_GET` | `0x06` | read stable identity/version/capabilities |
| `DEVICE_INFO_REPORT` | `0x07` | device information response |
| `NAME_SET` | `0x08` | change user-visible device name |
| `PASSWORD_SET` | `0x09` | change password verifier |
| `SETTINGS_RESULT` | `0x0A` | settings operation result |
| `TIME_SYNC` | `0x0B` | authenticated UTC anchor update |
| `STATE_GET` | `0x10` | request current device state |
| `STATE_REPORT` | `0x11` | hardware/application state snapshot |
| `MODE_SET` | `0x12` | request test mode |
| `COMMAND_RESULT` | `0x13` | mode command result |
| `IDENTIFY_START` | `0x14` | start physical identification indication |
| `IDENTIFY_STOP` | `0x15` | stop identification |
| `LOG_START` | `0x20` | begin journal export |
| `LOG_INFO` | `0x21` | journal metadata |
| `LOG_CHUNK` | `0x22` | journal page/chunk |
| `LOG_ACK` | `0x23` | acknowledge journal progress |
| `LOG_FINISH` | `0x24` | finish export request |
| `LOG_RESULT` | `0x25` | export completion/result |
| `LOG_HIST_GET` | `0x26` | request journal histogram |
| `LOG_HIST_REPORT` | `0x27` | histogram response |
| `KEEP_ALIVE` | `0x30` | authenticated one-way session activity |
| `ERROR` | `0x7F` | protocol/device error response |

The enum values must stay aligned between `mobile/wire/.../DplsProtocol.kt` and `firmware/include/dpls_protocol.h`.

## Authentication flow

```text
mobile                                      firmware
  |                                            |
  | HELLO(clientNonce[16])                     |
  |------------------------------------------->|
  |                                            |
  | AUTH_CHALLENGE                             |
  | sessionId LE32                             |
  | deviceNonce[16]                            |
  | salt[16]                                   |
  | initialized[1]                             |
  |<-------------------------------------------|
  |                                            |
  | AUTH_PROOF                                 |
  | clientNonce[16] + HMAC proof[32]           |
  |------------------------------------------->|
  |                                            |
  | AUTH_RESULT                                |
  | status[1] + retryAfter LE16                |
  | + token[8] when successful                 |
  |<-------------------------------------------|
```

The proof is derived by the shared Kotlin crypto implementation and verified by the firmware auth HAL. Authentication material belongs to the session lifecycle, not the UI.

After successful auth the mobile client is only `Synchronizing`. It requests state/device information and becomes `Online` only after authenticated `DEVICE_INFO_REPORT` proves the stable `NodeId`.

## First-time setup

When `AUTH_CHALLENGE.initialized == false`, the session enters commissioning. `SETUP` contains:

```text
sessionId LE32
nameLength U8
name UTF-8 bytes (max 31)
salt[16]
verifier[32]
```

Firmware may reboot after setup. Before stable identity is proven, the mobile client persists migration credentials only against the current BLE route; stable node-key persistence happens after later authenticated `DEVICE_INFO`.

## Authenticated payload prefix

Authenticated operations use a common 12-byte prefix:

```text
sessionId LE32
token[8]
```

Examples:

- `STATE_GET`: authenticated prefix;
- `DEVICE_INFO_GET`: authenticated prefix;
- `MODE_SET`: authenticated prefix + mode byte;
- `NAME_SET`: authenticated prefix + name length + name;
- `PASSWORD_SET`: authenticated prefix + new salt[16] + verifier[32];
- `TIME_SYNC`: authenticated prefix + Unix UTC seconds LE32;
- `LOG_START`: authenticated prefix;
- `LOG_ACK`: authenticated prefix + journal index LE16;
- `LOG_HIST_GET`: authenticated prefix + requested bucket count;
- `KEEP_ALIVE`: authenticated prefix.

## Device information

`DEVICE_INFO_REPORT` begins with:

```text
deviceId LE32
protocolVersion U8
firmwareVersion major/minor/patch U8/U8/U8
hardwareRevision U8
capabilities U8
reserved/current field U8
nameLength U8
name bytes
```

The mobile parser requires at least 12 bytes and uses the authenticated `deviceId` to construct `NodeId`.

## State report

The current extended parser supports a legacy prefix plus four independent voltages. Important semantics:

- mode and power source are explicit;
- `automaticReturnSeconds` reports remaining dangerous-mode time;
- uptime and revision are reported;
- validity bits determine whether a measurement is meaningful;
- `null` means invalid/unavailable measurement, while `0` means a valid zero-voltage measurement.

The extended report carries +1, +2, +T and reserve voltages as little-endian millivolts. Do not replace validity with `value + separate boolean` domain pairs; mobile encodes validity in nullable values.

## Command result

Protocol-v2 `COMMAND_RESULT` payload is 4 bytes:

```text
status U8
resultingMode U8
automaticReturnSeconds LE16
```

The response frame sequence, not the payload, correlates the result to the request.

Protocol-v2 `SETTINGS_RESULT` payload is one status byte. Legacy v1 8-byte/5-byte command/settings results are accepted only as decode compatibility.

## Journal records

A journal event record is 10 bytes:

```text
sequence LE32
timestampSeconds LE32
eventType U8
parameter U8
```

`timestampSeconds` may be Unix UTC or boot uptime depending on whether authenticated `TIME_SYNC` has established a UTC anchor since boot. See [`../timekeeping.md`](../timekeeping.md).

Journal paging is flow-controlled by `LOG_ACK`; the mobile `JournalMachine` owns paging state while firmware owns the persisted ring journal.

## Error and timeout rules

- a late response with a sequence that no longer matches the current operation must not complete that operation;
- a late error for an expired operation must not kill a newer operation;
- transport write completion is not protocol completion;
- operation timeouts are additionally guarded by the physical-link generation;
- firmware command idempotency is keyed by `(session_id, request_sequence)`.

## Change checklist

When changing the wire contract, update together:

1. `mobile/wire` frame/types/crypto code;
2. `mobile/core` payload builders/parsers when applicable;
3. `firmware/include/dpls_protocol.h` and server handlers;
4. cross-language CRC/wire contract checks;
5. common KMP byte-contract tests;
6. firmware protocol/server tests;
7. this document when the semantic contract changes.

Never rely on documentation alone to keep C and Kotlin aligned: executable contract tests remain required.