#!/usr/bin/env python3
"""Repository-specific architecture ownership guard.

These rules intentionally check narrow ownership invariants rather than generic
complexity. If a subsystem needs a new dependency, change the boundary first and
then change this file deliberately.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
SESSION = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/DeviceSession.kt"
SEQUENCER = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/session/DplsSession.kt"
ANDROID_BLE = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"

PHY = ROOT / "firmware/phy6252"
RUNTIME = PHY / "dpls_phy6252_runtime.c"
STORAGE = PHY / "dpls_phy6252_storage.c"
STORAGE_BLE = PHY / "dpls_phy6252_storage_ble.c"
STORAGE_FILES = {STORAGE, STORAGE_BLE}
TRANSPORT = PHY / "dpls_phy6252_transport.c"
MEASUREMENTS = PHY / "dpls_phy6252_measurements.c"
OUTPUTS = PHY / "dpls_phy6252_outputs.c"
SUPERVISOR = PHY / "dpls_phy6252_supervisor.c"
AUTH = PHY / "dpls_phy6252_auth.c"
GATT = PHY / "dpls_gatt_service.c"
TARGET = ROOT / "firmware/targets/phy6252/source/dplsBLEPeripheral.c"

violations: list[str] = []


def fail(path: Path, message: str) -> None:
    violations.append(f"{path.relative_to(ROOT)}: {message}")


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def require_text(path: Path, needle: str, message: str) -> None:
    if needle not in text(path):
        fail(path, message)


def forbid_text(path: Path, needle: str, message: str) -> None:
    if needle in text(path):
        fail(path, message)


def forbid_regex(path: Path, pattern: str, message: str) -> None:
    if re.search(pattern, text(path), flags=re.MULTILINE):
        fail(path, message)


# Mobile lifecycle has one owner.
require_text(SESSION, "sealed interface DeviceSession", "DeviceSession must own lifecycle state")
require_text(SESSION, "data class SessionChallenge", "challenge material must live in DeviceSession")
require_text(SESSION, "data class AuthSession", "authenticated wire material must live in DeviceSession")
require_text(SESSION, "data class Synchronizing(", "authentication must not imply verified identity")
require_text(SESSION, "data class Online(\n        val nodeId: NodeId,", "Online must require verified NodeId")
forbid_text(SESSION, "data class Online(\n        val nodeId: NodeId?", "Online may not contain unknown identity")
require_text(CLIENT, "private var session: DeviceSession", "controller must have one lifecycle owner")
require_text(CLIENT, "private fun projectSession", "UI lifecycle fields must project from DeviceSession")
require_text(CLIENT, "phase = connectionPhase(ui)", "UI phase must derive from DeviceSession")
for stale_owner in ("DplsSessionRuntime", "wireSession", "runtimeSession", "selectedAddress"):
    forbid_text(CLIENT, stale_owner, f"second session/route owner is forbidden: {stale_owner}")
for ui_truth in ("state.phase", "state.authenticated", "state.credentialsReady"):
    forbid_text(CLIENT, ui_truth, f"controller must not branch on UI projection {ui_truth}")
for number, line in enumerate(text(CLIENT).splitlines(), start=1):
    if re.match(r"^\s*phase\s*=", line) and "phase = connectionPhase(ui)" not in line:
        fail(CLIENT, f"line {number}: lifecycle phase must be projected")
for field in ("sessionId", "sessionToken", "clientNonce", "deviceNonce", "authSalt", "authenticated"):
    forbid_regex(CLIENT, rf"^\s*private\s+(?:var|val)\s+{field}\b",
                 f"{field} may not be stored independently in DplsClient")
require_text(SEQUENCER, "class FrameSequencer", "wire helper must be FrameSequencer only")
for secret in ("sessionId", "sessionToken", "clientNonce", "deviceNonce", "authSalt"):
    forbid_text(SEQUENCER, secret, f"FrameSequencer must not own {secret}")

legacy_command_id_paths = {"DplsControlMessages.kt", "DplsControlMessagesTest.kt"}
for path in (ROOT / "mobile").rglob("*.kt"):
    if path.name not in legacy_command_id_paths:
        forbid_regex(path, r"\bcommandId\b",
                     "second transaction id commandId is forbidden outside v1 compatibility")

require_text(CLIENT,
             "if (generation == linkGeneration && operation?.sequence == sequence) action()",
             "operation timeout must be correlated to link epoch and request sequence")
for generation in ("linkGeneration", "scanGeneration", "logTimeoutGeneration"):
    require_text(CLIENT, generation, f"missing stale-work generation guard: {generation}")

for path in (ROOT / "mobile/runtime/src/commonMain").rglob("*.kt"):
    source = text(path)
    for forbidden in ("android.", "androidx.compose", "platform.CoreBluetooth", ".core.domain.", ".core.app."):
        if forbidden in source:
            fail(path, f"runtime dependency leak: {forbidden}")
for path in (ROOT / "mobile/wire/src/commonMain").rglob("*.kt"):
    source = text(path)
    for forbidden in ("kotlinx.coroutines", "android.", "androidx.compose", "platform.CoreBluetooth", ".core.domain.", ".core.app."):
        if forbidden in source:
            fail(path, f"wire dependency leak: {forbidden}")
require_text(ANDROID_BLE,
             "BluetoothDevice.PHY_LE_1M_MASK,\n            handler,",
             "connectGatt callbacks must be main-Handler confined")

# PHY6252 runtime ownership. The old dpls_phy6252_app.c god-object is forbidden.
old_app = PHY / "dpls_phy6252_app.c"
if old_app.exists():
    fail(old_app, "monolithic PHY6252 app is forbidden; use runtime + adapters")
for required in (RUNTIME, STORAGE, STORAGE_BLE, TRANSPORT, MEASUREMENTS, OUTPUTS, SUPERVISOR, AUTH):
    if not required.exists():
        fail(required, "required PHY6252 runtime module is missing")

# SNV has one layer. It may span translation units (settings/journal and BLE key
# copies), but protocol/ADC/BLE/identity code cannot perform hidden SNV IO.
for path in PHY.glob("*.c"):
    if path not in STORAGE_FILES:
        forbid_text(path, "osal_snv_write", "SNV writes belong only to storage layer")
        forbid_text(path, "osal_snv_read", "SNV reads belong only to storage layer")

# Watchdog policy has one adapter. No random feed calls in domain/driver modules.
for path in PHY.glob("*.c"):
    if path != SUPERVISOR:
        forbid_text(path, "hal_watchdog_feed", "watchdog checkpoints belong only to supervisor")
        forbid_text(path, "watchdog_config", "watchdog timing belongs only to supervisor")
require_text(SUPERVISOR, "watchdog_config(WDG_8S)", "blocking flash IO needs bounded watchdog window")
require_text(SUPERVISOR, "watchdog_config(WDG_2S)", "normal SDK watchdog period must be restored")
require_text(RUNTIME, "dpls_phy6252_supervisor_checkpoint();",
             "1 Hz connected runtime must explicitly feed the SDK WDG_2S heartbeat")

# ADC IRQ and calibration work has one adapter.
for path in PHY.glob("*.c"):
    if path != MEASUREMENTS:
        forbid_text(path, '#include "adc.h"', "ADC dependency belongs only to measurements")
        forbid_regex(path, r"\bhal_adc_", "ADC calls belong only to measurements")
require_text(MEASUREMENTS, "osal_set_event(task_id, DPLS_PHY6252_ADC_EVT)",
             "ADC ISR must enqueue task work, not execute domain logic")
require_text(MEASUREMENTS, "ADC_BIT(DPLS_ADC_CHANNEL(DPLS_PIN_PORT1_ADC))",
             "rev2 port1 ADC contract missing")
require_text(MEASUREMENTS, "ADC_BIT(DPLS_ADC_CHANNEL(DPLS_PIN_VCAP_ADC))",
             "rev2 reserve ADC contract missing")
require_text(MEASUREMENTS, "DPLS_ADC_NEED_ALL", "connected sessions must sample all four channels")

# Runtime coordinates state machines only; it must not own driver details.
for forbidden in ('#include "adc.h"', '#include "osal_snv.h"', "osal_snv_", "hal_adc_", "GATT_Notification"):
    forbid_text(RUNTIME, forbidden, f"runtime must not own low-level dependency {forbidden}")
require_text(RUNTIME, "dpls_phy6252_storage_service_journal", "runtime must schedule storage explicitly")
require_text(RUNTIME, "dpls_phy6252_measurements_tick", "runtime must schedule measurements explicitly")
require_text(RUNTIME, "dpls_phy6252_transport_tick_security", "runtime must schedule link security explicitly")

# Journal append is RAM-only while linked. Flash is serviced only by the storage
# event after disconnect, which removes flash erase from BLE radio deadlines.
require_text(STORAGE, "pending_events[DPLS_PENDING_EVENT_CAPACITY]",
             "journal needs a RAM write-behind queue")
require_text(STORAGE, "if (link_active || pending_event_count == 0u)",
             "journal flash must be forbidden while BLE link is active")
require_text(TARGET, "DPLS_PHY6252_STORAGE_EVT", "OSAL must own a separate storage turn")
require_text(TARGET, "dpls_phy6252_runtime_process_storage();",
             "storage event must delegate to runtime")

# BLE callbacks enqueue only. TX pacing remains one-PDU-in-flight.
require_text(TRANSPORT, "osal_set_event(task_id, DPLS_PHY6252_RX_EVT)",
             "GATT RX must enqueue protocol work")
require_text(TRANSPORT, "tx.in_flight = true;", "TX must keep one PDU in flight")
require_text(TRANSPORT, "DPLS_TX_NOTIFY_PACE_MS 80u", "notification pacing contract missing")
require_text(GATT, "GATT_Notification", "TX must use notification when CCCD allows it")
require_text(ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsBle.kt",
             "byteArrayOf(0x03, 0x00)", "Android CCCD must remain notify+indicate")

# Crypto state cannot live on the 1 KiB OSAL stack.
require_text(AUTH, "static struct tc_hmac_state_struct hmac;",
             "HMAC state must stay off the OSAL stack")

# Target callback ordering remains compatible with the proven rc1 radio path.
require_text(TARGET, "return events ^ SBP_DPLS_LED_EVT",
             "LED event must not pump TX")
require_text(TARGET,
             "dpls_phy6252_runtime_process_rx();\n        schedule_led_if_needed();\n        return events ^ DPLS_PHY6252_RX_EVT;",
             "RX event must return before the TX turn")
forbid_text(TARGET, "~DPLS_PHY6252_TX_EVT", "RX must never clear a pending TX event")
require_text(TARGET, "uint8 update_enabled = FALSE",
             "slave connection-parameter update remains disabled")

require_text(ROOT / "firmware/sim/dpls_sim_transport.c",
             "pace_ms = dpls_sim_transport_cccd_notify(transport)",
             "host simulator must preserve ATT pacing")
require_text(ROOT / "firmware/sim/dpls_sim_board.c",
             "phy6252_emu_tick(&board->radio, board->now_ms);\n    dpls_sim_board_process_tx(board);",
             "sim timer and TX must stay separate turns")
if (ROOT / "firmware/phy6252_emu").exists():
    fail(ROOT / "firmware/phy6252_emu", "standalone PHY6252 emulator is forbidden")
require_text(ROOT / "firmware/src/dpls_server.c",
             "send_auth_result(s, f->sequence, DPLS_AUTH_DENIED, 0);\n        dpls_server_log(s, EVT_AUTH_FAILURE",
             "AUTH_RESULT must be queued before AUTH_FAILURE journal append")

for number, line in enumerate(text(CLIENT).splitlines(), start=1):
    stripped = line.strip()
    if "mutableState.value =" not in stripped:
        continue
    if "projectSession(" in stripped or stripped.startswith("private val mutableState"):
        continue
    fail(CLIENT, f"line {number}: direct UI state replacement bypasses session projection")

if violations:
    print("Architecture guard failed:")
    for item in violations:
        print(f"  - {item}")
    raise SystemExit(1)

print("Architecture guard: OK")
print("  mobile lifecycle owner: DeviceSession")
print("  PHY6252 coordinator: runtime")
print("  flash/SNV owner: storage layer")
print("  ADC owner: measurements")
print("  BLE queue/security owner: transport")
print("  watchdog policy owner: supervisor")
