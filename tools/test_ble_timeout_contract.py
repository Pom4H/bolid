#!/usr/bin/env python3
"""RC9 timing and power architecture invariants.

Time may bound waiting or schedule physical sampling, but correctness must not
rely on one layer timing out before another layer. Sleep locks are explicit
resource owners: link / dangerous outputs / ADC conversion series.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"
ANDROID_PLATFORM = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidPlatformServices.kt"
IOS_PLATFORM = ROOT / "mobile/core/src/iosMain/kotlin/ru/bolid/testdpls/core/app/IosPlatform.kt"
WEB_PLATFORM = ROOT / "mobile/web/src/wasmJsMain/kotlin/ru/bolid/testdpls/web/LabPlatformServices.kt"
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
TRANSPORT = ROOT / "firmware/phy6252/dpls_phy6252_transport.c"
RUNTIME = ROOT / "firmware/phy6252/dpls_phy6252_runtime.c"
TARGET = ROOT / "firmware/targets/phy6252/source/dplsBLEPeripheral.c"
TARGET_H = ROOT / "firmware/targets/phy6252/source/simpleBLEPeripheral.h"
MEASUREMENTS = ROOT / "firmware/phy6252/dpls_phy6252_measurements.c"
OUTPUTS = ROOT / "firmware/phy6252/dpls_phy6252_outputs.c"

android = ANDROID.read_text(encoding="utf-8")
android_platform = ANDROID_PLATFORM.read_text(encoding="utf-8")
ios_platform = IOS_PLATFORM.read_text(encoding="utf-8")
web_platform = WEB_PLATFORM.read_text(encoding="utf-8")
client = CLIENT.read_text(encoding="utf-8")
transport = TRANSPORT.read_text(encoding="utf-8")
runtime = RUNTIME.read_text(encoding="utf-8")
target = TARGET.read_text(encoding="utf-8")
target_h = TARGET_H.read_text(encoding="utf-8")
measurements = MEASUREMENTS.read_text(encoding="utf-8")
outputs = OUTPUTS.read_text(encoding="utf-8")

# Android pairing must not introduce a second competing product deadline.
for forbidden in ("PAIRING_TIMEOUT_MS", "pairingTimeout"):
    if forbidden in android:
        raise SystemExit(f"Android transport owns forbidden pairing deadline: {forbidden}")

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

# A request arriving after a domain deadline must not refresh activity before the
# overdue safety/identify state is reconciled. This is the key packet-vs-timer
# commutativity invariant of RC9.
try:
    rx_body = runtime.split("void dpls_phy6252_runtime_process_rx(void)", 1)[1].split(
        "void dpls_phy6252_runtime_process_adc(void)", 1
    )[0]
except IndexError as exc:
    raise SystemExit("cannot isolate runtime_process_rx") from exc
first_tick = rx_body.find("dpls_server_tick(&server, now);")
receive = rx_body.find("dpls_server_receive(&server, frame, length, now)")
second_tick = rx_body.find("dpls_server_tick(&server, now);", first_tick + 1)
if first_tick < 0 or receive < 0 or second_tick < 0 or not (first_tick < receive < second_tick):
    raise SystemExit("RX must reconcile domain deadlines both before and after request processing")

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
if runtime.count("dpls_server_tick(&server, now);") < 3:
    raise SystemExit("runtime must reconcile safety on RX, ADC and deadline events")

# PHY6252 power ownership is deliberate. MOD_USR0 is retained for the entire
# connected session because real PB-03F/SDK 3.1.2 testing exposed an ADC/radio
# sleep race. MOD_USR1 protects energized dangerous GPIO. MOD_USR2 is held only
# while an ADC conversion series is active. Do not trade these reliability
# barriers for lower current without a separate real-hardware proof.
for owner in ("MOD_USR0", "MOD_USR1", "MOD_USR2"):
    if f"hal_pwrmgr_register({owner}" not in target:
        raise SystemExit(f"sleep owner registration missing: {owner}")
if "hal_pwrmgr_lock(MOD_USR0)" not in target or "hal_pwrmgr_unlock(MOD_USR0)" not in target:
    raise SystemExit("BLE link sleep guard MOD_USR0 must remain symmetric")
if "hal_pwrmgr_lock(MOD_USR1)" not in outputs or "hal_pwrmgr_unlock(MOD_USR1)" not in outputs:
    raise SystemExit("dangerous-output sleep guard MOD_USR1 missing")
if "hal_pwrmgr_lock(MOD_USR2)" not in measurements or "hal_pwrmgr_unlock(MOD_USR2)" not in measurements:
    raise SystemExit("ADC conversion sleep guard MOD_USR2 missing")
if measurements.count("hal_pwrmgr_lock(MOD_USR2)") != 1:
    raise SystemExit("ADC sleep lock must stay centralized in adc_sleep_guard")

# nowMillis is intentionally epoch-compatible because TIME_SYNC uses it, but its
# progression must come from a monotonic clock on real platforms.
if "SystemClock.elapsedRealtime()" not in android_platform:
    raise SystemExit("Android nowMillis must advance from elapsedRealtime")
if "NSProcessInfo.processInfo.systemUptime" not in ios_platform:
    raise SystemExit("iOS nowMillis must advance from systemUptime")
if "performanceNow()" not in web_platform:
    raise SystemExit("Web lab nowMillis must advance from performance.now")

# Keep the existing mobile deadline only as local liveness/resource reclamation.
# Its numeric relation to the independent firmware deadline is deliberately not
# inspected or constrained here.
if "CONNECT_TIMEOUT_MS" not in client:
    raise SystemExit("mobile connect liveness deadline unexpectedly missing")

print("RC9 timing/power contract: PASS (commutative RX deadlines, one-shot runtime, monotonic clocks, explicit sleep owners)")
