# Hardware revision 2

This page is a developer map of the current Test-DPLS hardware contract. The authoritative pin assignment is `firmware/phy6252/dpls_board.h`; copied tables here are for navigation and review, not a second source of truth.

## Control outputs

Logic is 3.3 V, active-high. Hardware pull-downs make all-zero control outputs the fail-safe `NORMAL` state.

| Signal | GPIO | Purpose |
|---|---|---|
| `ISO_1` | P31 | isolation control 1 |
| `ISO_2` | P32 | isolation control 2 / main-line open mode |
| `ISO_T` | P33 | +T isolation |
| `KZ_1` | P14 | +1 short mode |
| `KZ_2` | P16 | +2 short mode |
| `KZ_T` | P17 | +T short mode |

Current mode mapping:

| Mode | Energized signal |
|---|---|
| `NORMAL` | none |
| `OPEN_T` | `ISO_T` |
| `OPEN_MAIN` | `ISO_2` |
| `SHORT_1` | `KZ_1` |
| `SHORT_2` | `KZ_2` |
| `SHORT_T` | `KZ_T` |

Firmware must switch break-before-make so two incompatible outputs are not energized during a transition.

## ADC inputs

Revision 2 uses four independent single-ended PHY6252 ADC inputs:

| Measurement | GPIO | SDK channel |
|---|---|---|
| +1 | P20 | `ADC_CH3P_P20` |
| +2 | P15 | `ADC_CH3N_P15` |
| +T | P24 | `ADC_CH2N_P24` |
| reserve / VCAP | P23 | `ADC_CH1P_P23` |

Each external DPLS input requires its own high-impedance divider/protection path for the 0–30 V measurement range. DPLS line voltage must never be applied directly to a GPIO.

The firmware scans the channels sequentially and only marks a value valid after that input has a completed measurement. In the mobile domain:

- `null` means no valid measurement;
- `0` means a valid measured zero.

The four-channel telemetry requirement is a project extension beyond the minimum source specification; details and acceptance criteria are in [`../live-voltage-requirements.md`](../live-voltage-requirements.md).

## LED

Current RGB mapping:

| Channel | GPIO |
|---|---|
| red | P07 |
| green | P11 |
| blue | P18 |

The existing indication scenes primarily use green. Exact timing/scene precedence belongs to `firmware/src/dpls_led.c` and `firmware/include/dpls_led.h`; hardware acceptance is listed in the bring-up checklist.

Do not move the green channel to P15/P24 or other pins already committed to measurement. Compile-time board assertions intentionally catch mapping drift.

## Factory reset

Physical factory reset is P34.

P24 is not available for reset because it is the +T ADC input. The board header contains compile-time checks for this conflict.

The current acceptance behavior is a physical P34-to-GND hold of at least 5 seconds, clearing initialization/settings and BLE bond-related state as defined by the target integration. Remote BLE factory reset is not part of the current product contract.

## SNV / flash allocation

The current PHY6252 persistence allocation documented by the target is:

| Record/range | Data |
|---|---|
| `0x20..0x5F` | BLE bonds |
| `0x80` | settings |
| `0x81` | initialization marker |
| `0x82` | device/BLE address data |
| `0x83` | ADC calibration |
| `0x84` | authentication lock |
| `0x90..0xA3` | event journal |

The filesystem is mounted at `0x1103C000` for three sectors.

A normal application flash is intended to preserve SNV. `tools/flash_firmware.sh ... --erase` clears SNV as well as application flash and must be treated as destructive to settings/bonds/calibration/journal.

## Reserve and power-source behavior

The target derives power-source/reserve state from ADC/hardware observations. Current preliminary thresholds are documented in the bring-up checklist and must be confirmed against the real power-stage divider and discharge curve before being treated as final electrical values.

Safety behavior does not wait for the mobile app: low reserve can force an energized test mode back to `NORMAL` in firmware.

## Real-short / automatic isolation

The current firmware observes real-short/auto-isolation state and prevents unsafe requested modes while it is active. Physical isolation timing is a hardware acceptance property, not something host tests can prove.

Use [`../bring-up-checklist.md`](../bring-up-checklist.md) for the required real-device checks.

## PHY6252 constraints that affect board changes

Several pin/memory assumptions are target-specific:

- P16/P17 are also crystal-capable pins at silicon level; the current board intentionally uses them as KZ outputs;
- the production target is built against pinned PHY62XX SDK 3.1.2;
- retained SRAM and XIP placement are constrained;
- changing ADC routing can interact with differential-pair/PGA functions exposed by the SDK.

Read [`../phy6252-programmer-reference.md`](../phy6252-programmer-reference.md) before changing the board mapping or target configuration.

## Board-change checklist

A hardware mapping change should update in one change set:

1. `firmware/phy6252/dpls_board.h`;
2. the PHY6252 adapter using the affected GPIO/ADC channel;
3. compile-time board assertions;
4. CI pinout contract;
5. this page and any affected acceptance requirement;
6. real hardware bring-up results.

Do not change a copied documentation table without changing the code source of truth.