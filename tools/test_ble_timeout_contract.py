#!/usr/bin/env python3
"""RC9 timing, BLE-profile and power architecture invariants.

Correctness may use absolute deadlines, but must not depend on independent timer
ordering. One runtime timer owns application wakeups. One power module owns every
pwrmgr lock so sleeping becomes an explicit resource policy.
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
POWER = ROOT / "firmware/phy6252/dpls_phy6252_power.c"
POWER_H = ROOT / "firmware/phy6252/dpls_phy6252_power.h"

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
power = POWER.read_text(encoding="utf-8")
power_h = POWER_H.read_text(encoding="utf-8")

for forbidden in ("PAIRING_TIMEOUT_MS", "pairingTimeout"):
    if forbidden in android:
        raise SystemExit(f"Android transport owns forbidden pairing deadline: {forbidden}")

# No global correctness poll and no second LED timer.
for forbidden in (
    "DPLS_TICK_MS",
    "DPLS_TICK_IDLE_MS",
    "SBP_DPLS_TICK_EVT",
    "SBP_DPLS_LED_EVT",
):
    if forbidden in target or forbidden in target_h or forbidden in runtime:
        raise SystemExit(f"periodic/duplicate application timer returned: {forbidden}")

for required in (
    "dpls_phy6252_runtime_next_wakeup_ms",
    "dpls_phy6252_runtime_process_timer",
    "server_next_deadline_ms",
    "next_led_ms",
    "DPLS_MEASUREMENT_DANGEROUS_MS",
    "DPLS_MEASUREMENT_RESERVE_MS",
    "DPLS_MEASUREMENT_CONNECTED_NORMAL_MS",
    "DPLS_MEASUREMENT_IDLE_MS",
):
    if required not in runtime:
        raise SystemExit(f"runtime one-shot/adaptive scheduler missing: {required}")

if target.count("osal_start_timerEx(app_task_id, SBP_DPLS_TIMER_EVT") != 1:
    raise SystemExit("target must expose exactly one application timer start site")
if "osal_stop_timerEx(app_task_id, SBP_DPLS_TIMER_EVT)" not in target:
    raise SystemExit("runtime timer must be replaceable after semantic state changes")

# Packet-vs-deadline commutativity.
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
    raise SystemExit("RX must reconcile deadlines both before and after request processing")

# TX completion is real ATT completion or notification host acceptance, never a
# guessed sleep/pacing interval.
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

if "update_power_state(mode);" not in measurements:
    raise SystemExit("ADC completion does not reconcile derived safety state")
if runtime.count("dpls_server_tick(&server, now);") < 3:
    raise SystemExit("runtime must reconcile safety on RX, ADC and deadline events")

# Centralized power ownership: no driver/target may call lock/unlock directly.
for source_name, source in (("target", target), ("outputs", outputs), ("measurements", measurements)):
    for forbidden in ("hal_pwrmgr_lock(", "hal_pwrmgr_unlock(", "hal_pwrmgr_register("):
        if forbidden in source:
            raise SystemExit(f"{source_name} bypasses centralized power manager: {forbidden}")
for owner in ("MOD_USR0", "MOD_USR1", "MOD_USR2"):
    if f"hal_pwrmgr_register({owner}" not in power:
        raise SystemExit(f"power manager registration missing: {owner}")
for required in (
    "DPLS_POWER_LINK",
    "DPLS_POWER_OUTPUT",
    "DPLS_POWER_ADC",
    "dpls_phy6252_power_acquire",
    "dpls_phy6252_power_release",
    "dpls_phy6252_power_snapshot",
):
    if required not in power + power_h:
        raise SystemExit(f"centralized power contract missing: {required}")
if "#define DPLS_CONNECTED_SLEEP 1" not in power_h:
    raise SystemExit("RC9 low-power candidate must default to connected sleep for Monday measurement")

# Connection interval is semantic policy: active while auth/dangerous, relaxed
# only in authenticated NORMAL. No automatic vendor timer owns this transition.
for required in (
    "GAPRole_SendUpdateParam",
    "DPLS_ACTIVE_MIN_CONN_INTERVAL",
    "DPLS_IDLE_MIN_CONN_INTERVAL",
    "DPLS_IDLE_SLAVE_LATENCY",
    "dpls_phy6252_runtime_link_profile",
):
    if required not in target + runtime:
        raise SystemExit(f"adaptive BLE connection profile missing: {required}")
if "uint8 update_enabled = FALSE;" not in target:
    raise SystemExit("vendor automatic param-update timer must remain disabled")

# nowMillis is epoch-compatible for TIME_SYNC but progresses monotonically.
if "SystemClock.elapsedRealtime()" not in android_platform:
    raise SystemExit("Android nowMillis must advance from elapsedRealtime")
if "NSProcessInfo.processInfo.systemUptime" not in ios_platform:
    raise SystemExit("iOS nowMillis must advance from systemUptime")
if "performanceNow()" not in web_platform:
    raise SystemExit("Web lab nowMillis must advance from performance.now")

if "CONNECT_TIMEOUT_MS" not in client:
    raise SystemExit("mobile connect liveness deadline unexpectedly missing")

print("RC9 timing/power contract: PASS")
print("  one application timer: ADC + LED + semantic/transport deadlines")
print("  one pwrmgr owner with connected-sleep A/B switch and hold-time diagnostics")
print("  adaptive BLE active/idle profile follows auth + safety state")
