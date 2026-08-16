# Firmware runtime

The firmware is split into a portable C99 core and a narrow PHY6252 adapter. Protocol, safety and most state-machine behavior are host-testable without the vendor SDK.

## Runtime structure

```text
BLE/GATT bytes
    |
    v
dpls_server_receive()
    |
    +--> protocol decode/validation
    +--> auth/setup/settings handlers
    +--> mode command handlers
    +--> state/device-info handlers
    +--> journal handlers
    |
    v
dpls_server_t
  session
  clock
  identify
  observed_inputs
  command_cache
  journal
  safety
    |
    v
HAL capabilities
  link / hardware / settings / auth / events
    |
    v
PHY6252 adapter
```

The portable core must not include vendor SDK headers. Target-specific behavior belongs under `firmware/phy6252` or `firmware/targets/phy6252`.

## Public event surface

The server is driven by a small event API:

```c
dpls_server_init(...)
dpls_server_connected(...)
dpls_server_disconnected(...)
dpls_server_receive(...)
dpls_server_tick(...)
dpls_server_log(...)
```

Think of `dpls_server` as a state machine receiving link events, protocol frames and monotonic time ticks. PHY6252 code should translate hardware/SDK events into this API instead of duplicating product rules.

## HAL composition

`dpls_hal_t` is composed from cohesive capability groups:

- link: encryption status, indication send, disconnect;
- hardware: apply/safe mode, voltage channels, power source, reserve/short status, LED and device info;
- settings: initialization state, salt/verifier/name persistence;
- auth: random bytes, proof verification and persistent lock state;
- events: journal initialization/append/read;
- diagnostic error callback.

Do not flatten these back into unrelated global calls or add generic Manager/Repository/UseCase layers around one implementation.

## Safety owner

`dpls_safety` is the single owner of:

```text
current dangerous mode
mode deadline
safety revision
forced-return precedence
```

Inputs are an immutable snapshot for the current decision:

```text
connected
authenticated
reserve_low
real_short
last_authenticated_activity_ms
```

Current hard limits from `dpls_safety.h`:

- dangerous mode maximum: 300,000 ms;
- authenticated session inactivity timeout: 10,000 ms.

### Forced return

Firmware may force `NORMAL` because of:

- mode deadline;
- session inactivity;
- low reserve;
- disconnect;
- real short / automatic isolation.

Boot/error paths also establish safe `NORMAL` through the server/hardware layer.

A failed `hardware.apply_mode()` must force both physical outputs and logical safety state to `NORMAL`. Physical/logical split-brain is not an acceptable failure mode.

## Output switching

Hardware revision 2 uses active-high control signals. All six control outputs low is safe `NORMAL`.

Mode mapping:

```text
OPEN_T       -> ISO_T
OPEN_MAIN    -> ISO_2
SHORT_1      -> KZ_1
SHORT_2      -> KZ_2
SHORT_T      -> KZ_T
NORMAL       -> all controls low
```

The hardware adapter must preserve break-before-make behavior when changing energized modes.

## Tick model

The server tick uses monotonic milliseconds. Safety deadlines must never depend on wall-clock/UTC time.

The PHY6252 integration samples power source, reserve-low and real-short into a coherent observation before applying edge logging and safety decisions. Avoid reading changing hardware inputs independently throughout one logical safety decision.

See [`../timekeeping.md`](../timekeeping.md) for the separation between monotonic deadlines and UTC journal timestamps.

## Authentication

Application authentication is independent from BLE transport encryption.

Current constants:

- nonce: 16 bytes;
- salt: 16 bytes;
- proof: 32 bytes;
- session token: 8 bytes;
- maximum failed attempts: 5;
- persistent authentication block: 300 seconds;
- minimum auth attempt interval: 1 second;
- setup window: 300 seconds.

Persistent auth lock state is stored through the auth HAL. Session tokens and nonces are runtime state and must be cleared when the link/session resets.

## Command idempotency

Protocol v2 correlates by frame sequence. Firmware command replay/idempotency is keyed by the authenticated session and request sequence, not by a second command id.

The command cache is bounded (`DPLS_COMMAND_CACHE_SIZE`). Keep replay behavior deterministic and covered by server tests when modifying command handling.

## Journal

Firmware owns persisted journal storage and export data. `dpls_server_journal_t` tracks:

- count;
- next sequence;
- active export state;
- export count/first sequence.

Event storage is abstracted through the event-store HAL so ring/persistence behavior can be host-tested separately from PHY6252 flash integration.

The current project capacity is 200 events unless overridden at build time.

## GATT TX/RX behavior

The target GATT adapter must preserve backpressure:

- RX callback returns ATT status; queue-full is reported as insufficient resources instead of dropping the write;
- TX uses indications;
- only one indication is in flight;
- queue advancement waits for ATT confirmation;
- transient allocation/send errors are retried by the target adapter;
- confirmation timeout is treated as a failure condition.

Do not reintroduce a parallel notification path that bypasses the serialized indication contract.

## PHY6252 scheduling

The adapter exposes OSAL events for RX, TX and ADC processing. Raw ADC capture stays minimal on the interrupt path; scaling/calibration/window averaging runs in the OSAL task.

Sleep/power integration has target-specific constraints documented in [`../phy6252-programmer-reference.md`](../phy6252-programmer-reference.md), including SRAM retention, filesystem initialization and watchdog behavior.

## Persistence boundary

The portable server asks HALs to persist settings, auth lock and journal records. PHY6252 SNV allocation is a target contract, documented in [Hardware revision 2](hardware-rev2.md) and `firmware/README.md`.

Flashing with a full erase destroys SNV. Normal application update is intended to preserve it.

## Testing rule

Put firmware behavior at the lowest layer that proves it once:

- frame/CRC -> protocol tests;
- safety precedence/deadlines -> `test_safety.c`;
- auth/setup/commands/journal/idempotency -> `test_server_v2.c`;
- LED scenes -> `test_led.c`;
- calibration -> `test_calib.c`;
- ADC interrupt/task model -> `test_adc_irq_model.c`;
- physical GPIO/ADC/current behavior -> real bring-up/E2E.

See [Build, test and flash](build-test-flash.md) for commands.