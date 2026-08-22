#!/usr/bin/env python3
"""RC9 timing architecture invariants.

Time may bound waiting or schedule physical sampling, but correctness must not
rely on one layer timing out before another layer.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
TRANSPORT = ROOT / "firmware/phy6252/dpls_phy6252_transport.c"
RUNTIME = ROOT / "firmware/phy6252/dpls_phy6252_runtime.c"
TARGET = ROOT / "firmware/targets/phy6252/source/dplsBLEPeripheral.c"
TARGET_H = ROOT / "firmware/targets/phy6252/source/simpleBLEPeripheral.h"
MEASUREMENTS = ROOT / "firmware/phy6252/dpls_phy6252_measurements.c"

android = ANDROID.read_text(encoding="utf-8")
client = CLIENT.read_text(encoding="utf-8")
transport = TRANSPORT.read_text(encoding="utf-8")
runtime = RUNTIME.read_text(encoding="utf-8")
target = TARGET.read_text(encoding="utf-8")
target_h = TARGET_H.read_text(encoding="utf-8")
measurements = MEASUREMENTS.read_text(encoding="utf-8")

# Android pairing must not introduce a second competing product deadline.
for forbidden in ("PAIRING_TIMEOUT_MS", "pairingTimeout"):
    if forbidden in android:
        raise SystemExit(f"Android transport owns forbidden pairing deadline: {forbidden}")

# Mobile and firmware may each reclaim resources, but CI must never encode an
# ordering relation between those independent deadlines again.
if "firmware_ms - connect_ms" in client or "firmware_ms - connect_ms" in transport:
    raise SystemExit("cross-layer timeout ordering returned")

# The old global correctness tick is gone. Periodic ADC sampling is explicitly
# allowed because physics must be observed; safety reacts at ADC completion.
for forbidden in ("DPLS_TICK_MS", "DPLS_TICK_IDLE_MS", "SBP_DPLS_TICK_EVT"):
    if forbidden in target or forbidden in target_h or forbidden in runtime:
        raise SystemExit(f"periodic correctness tick returned: {forbidden}")

for required in (
    "dpls_phy6252_runtime_next_wakeup_ms",
    "dpls_phy6252_runtime_process_timer",
    "server_next_deadline_ms",
    "DPLS_MEASUREMENT_ACTIVE_MS",
    "DPLS_MEASUREMENT_IDLE_MS",
):
    if required not in runtime:
        raise SystemExit(f"runtime one-shot scheduler missing: {required}")

if "osal_stop_timerEx(app_task_id, SBP_DPLS_TIMER_EVT)" not in target:
    raise SystemExit("target must own exactly one replaceable runtime timer")

# TX completion may be an ATT confirmation or a successful unconfirmed
# notification. There must be no guessed 80 ms completion/pacing timer.
for forbidden in ("DPLS_TX_NOTIFY_PACE_MS", "transport_tick_tx", "transport_tick_security"):
    if forbidden in transport:
        raise SystemExit(f"transport timing heuristic returned: {forbidden}")

for required in (
    "dpls_phy6252_transport_check_deadlines",
    "dpls_phy6252_transport_next_deadline_ms",
    "DPLS_TX_CONFIRM_TIMEOUT_MS",
    "DPLS_TX_RETRY_MIN_MS",
    "DPLS_TX_RETRY_MAX_MS",
):
    if required not in transport:
        raise SystemExit(f"transport deadline/backoff contract missing: {required}")

# Safety inputs derived from ADC must be reconciled in the ADC event path, not
# delayed until the next sampling timer.
if "update_power_state(mode);" not in measurements:
    raise SystemExit("ADC completion does not reconcile derived safety state")
if "dpls_server_tick(&server, now);" not in runtime:
    raise SystemExit("runtime does not reconcile safety on events")

# Keep the existing mobile deadline, but only as local liveness/resource
# reclamation. Its numeric relation to firmware is intentionally irrelevant.
if "CONNECT_TIMEOUT_MS" not in client:
    raise SystemExit("mobile connect liveness deadline unexpectedly missing")

print("RC9 timing contract: PASS (independent deadlines, one-shot runtime, no TX pacing guess)")
