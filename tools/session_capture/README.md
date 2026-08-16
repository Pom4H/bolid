# Session capture → simulator fidelity

Goal: record how a **real phone ↔ real PHY6252 board** behaves in real time, then
use those traces to harden `firmware/sim/dpls_simulator.c` and soft-BLE scenarios.

This is scaffolding — not a claim that the host simulator equals the Chinese board.

## Quick path

```sh
# 1) Debug APK on a phone, board powered and advertising
# 2) Start capture (Ctrl-C to stop, or --duration N)
python3 tools/session_capture/record_session.py --name lab
# optional UART alongside logcat:
python3 tools/session_capture/record_session.py --name lab --serial /dev/ttyUSB0

# 3) Parse into NDJSON + FRAME list
python3 tools/session_capture/parse_session.py tmp/sessions/session-*-lab.log

# 4) Replay phone→device writes into host simulator
cmake -S firmware -B firmware/build && cmake --build firmware/build --target dpls_simulator
python3 tools/session_capture/replay_to_simulator.py \
  tmp/sessions/session-*-lab.frames.txt \
  --simulator firmware/build/dpls_simulator
```

Artifacts land in `tmp/sessions/` (gitignored via `tmp/`).

## What is recorded

| Source | Tag / stream | Content |
|--------|--------------|---------|
| Android BLE | `TestDplsBle` | GATT lifecycle, `TX write … hex=`, `RX indication … hex=` |
| DplsClient | `TestDplsSession` (+ mirrored to Ble) | `E2E …`, `STATE mode=… line_mv=…`, journal markers |
| E2E driver | `TestDplsE2e` | mode/export automation |
| UART (optional) | serial | boot / vendor noise until board diag UART exists |

## Offline smoke (no hardware)

```sh
python3 tools/session_capture/test_session_capture.py
```

## Next fidelity work (from real traces)

1. Diff simulator `TX` indications vs captured `RX` for the same request sequence.
2. Codify mismatches as host tests / soft-BLE scenarios (`REAL_SHORT`, `RESERVE_LOW`, timing).
3. Optionally add PHY6252 UART breadcrumbs mirroring simulator `MODE`/`LED`/`DIAG` lines.
