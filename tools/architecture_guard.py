#!/usr/bin/env python3
"""Проверки архитектурных, ownership- и safety-инвариантов репозитория.

Проверяем только правила, которые можно надёжно выразить по исходному коду.
Поведенческие детали по-прежнему должны подтверждаться обычными тестами.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
CONNECTION_ACTOR = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/ConnectionActor.kt"
CONNECTION_MACHINE = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/ConnectionMachine.kt"
SESSION = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/DeviceSession.kt"
SEQUENCER = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/session/DplsSession.kt"
ANDROID_BLE = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"
ANDROID_SECURITY = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidGattSecurityPolicy.kt"
IOS_BLE = ROOT / "mobile/core/src/iosMain/kotlin/ru/bolid/testdpls/core/app/IosBleTransport.kt"
GATT = ROOT / "firmware/phy6252/dpls_gatt_service.c"
PHY_APP = ROOT / "firmware/phy6252/dpls_phy6252_app.c"
PHY_APP_H = ROOT / "firmware/phy6252/dpls_phy6252_app.h"
PHY_TARGET = ROOT / "firmware/targets/phy6252/source/dplsBLEPeripheral.c"
PHY_STORAGE = ROOT / "firmware/phy6252/dpls_phy6252_storage.c"
PHY_SNV = ROOT / "firmware/phy6252/dpls_phy6252_snv_guard.c"
SAFETY = ROOT / "firmware/src/dpls_safety.c"
SERVER = ROOT / "firmware/src/dpls_server.c"

violations: list[str] = []
FLAGS = re.MULTILINE | re.DOTALL


def source(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def fail(path: Path, message: str) -> None:
    violations.append(f"{path.relative_to(ROOT)}: {message}")


def require_text(path: Path, needle: str, message: str) -> None:
    if needle not in source(path):
        fail(path, message)


def forbid_text(path: Path, needle: str, message: str) -> None:
    if needle in source(path):
        fail(path, message)


def require_regex(path: Path, pattern: str, message: str) -> None:
    if re.search(pattern, source(path), flags=FLAGS) is None:
        fail(path, message)


def forbid_regex(path: Path, pattern: str, message: str) -> None:
    if re.search(pattern, source(path), flags=FLAGS) is not None:
        fail(path, message)


# Lifecycle продукта и идентичность транзакций.
require_text(SESSION, "sealed interface DeviceSession", "DeviceSession must define lifecycle state")
require_text(SESSION, "data class SessionChallenge", "challenge material must live in DeviceSession")
require_text(SESSION, "data class AuthSession", "authenticated wire material must live in DeviceSession")
require_text(SESSION, "data class Synchronizing(", "authentication must not imply verified identity")
require_regex(SESSION, r"data\s+class\s+Online\s*\(\s*val\s+nodeId\s*:\s*NodeId\s*,", "Online must require verified NodeId")
forbid_regex(SESSION, r"data\s+class\s+Online\s*\(\s*val\s+nodeId\s*:\s*NodeId\?", "Online may not contain unknown identity")
require_text(CLIENT, "private val connection = ConnectionActor()", "ConnectionActor must own product lifecycle")
require_text(CLIENT, "get() = connection.state", "DplsClient may only read lifecycle through ConnectionActor")
forbid_regex(CLIENT, r"private\s+var\s+session\s*:\s*DeviceSession", "second mutable DeviceSession owner is forbidden")
require_text(CLIENT, "connection.dispatch(event)", "DplsClient lifecycle mutations must dispatch semantic events")
require_text(CONNECTION_ACTOR, "ConnectionMachine.reduce(state, event)", "ConnectionActor must delegate transitions to reducer")
require_text(CONNECTION_MACHINE, "fun reduce(state: DeviceSession, event: ConnectionEvent)", "connection reducer must stay explicit and pure")
for path in (CLIENT, CONNECTION_ACTOR):
    forbid_text(path, "transitionTo(", "direct desired-state compatibility bridge is forbidden")
for event_name in (
    "ConnectRequested",
    "LinkConnected",
    "Subscribed",
    "ChallengeReceived",
    "Authenticated",
    "IdentityVerified",
    "LinkLost",
    "BluetoothUnavailable",
    "BluetoothAvailable",
    "Failed",
    "Reset",
):
    require_text(CLIENT, f"ConnectionEvent.{event_name}", f"missing semantic connection event: {event_name}")
require_text(CLIENT, "private fun projectSession", "UI lifecycle must be projected")
require_text(CLIENT, "phase = connectionPhase(ui)", "UI phase must derive from DeviceSession")
for stale_owner in ("DplsSessionRuntime", "wireSession", "runtimeSession", "selectedAddress"):
    forbid_text(CLIENT, stale_owner, f"second session/route owner is forbidden: {stale_owner}")
for ui_truth in ("state.phase", "state.authenticated", "state.credentialsReady"):
    forbid_text(CLIENT, ui_truth, f"controller must not branch on UI projection {ui_truth}")
for field in ("sessionId", "sessionToken", "clientNonce", "deviceNonce", "authSalt", "authenticated"):
    forbid_regex(CLIENT, rf"^\s*private\s+(?:var|val)\s+{field}\b", f"independent lifecycle field forbidden: {field}")
require_text(SEQUENCER, "class FrameSequencer", "wire helper must be sequence-only")
for secret in ("sessionId", "sessionToken", "clientNonce", "deviceNonce", "authSalt"):
    forbid_text(SEQUENCER, secret, f"FrameSequencer must not own {secret}")
legacy_command_id_paths = {"DplsControlMessages.kt", "DplsControlMessagesTest.kt"}
for path in (ROOT / "mobile").rglob("*.kt"):
    if path.name not in legacy_command_id_paths:
        forbid_regex(path, r"\bcommandId\b", "second transaction id commandId is forbidden")
require_regex(CLIENT, r"generation\s*==\s*linkGeneration\s*&&\s*operation\?\.sequence\s*==\s*sequence", "operation timeout must check link epoch and request sequence")
for generation in ("linkGeneration", "scanGeneration", "logTimeoutGeneration"):
    require_text(CLIENT, generation, f"missing stale-work generation: {generation}")

# Runtime и wire не зависят от UI и платформенных BLE API.
for path in (ROOT / "mobile/runtime/src/commonMain").rglob("*.kt"):
    for forbidden in ("android.", "androidx.compose", "platform.CoreBluetooth", ".core.domain.", ".core.app."):
        if forbidden in source(path):
            fail(path, f"runtime dependency leak: {forbidden}")
for path in (ROOT / "mobile/wire/src/commonMain").rglob("*.kt"):
    for forbidden in ("kotlinx.coroutines", "android.", "androidx.compose", "platform.CoreBluetooth", ".core.domain.", ".core.app."):
        if forbidden in source(path):
            fail(path, f"wire dependency leak: {forbidden}")

# Общий BLE security contract для Android и iOS.
require_regex(GATT, r"dpls_rx_uuid\s*\}\s*,\s*GATT_PERMIT_WRITE\s*\|\s*GATT_PERMIT_ENCRYPT_WRITE", "RX must remain the encrypted security boundary")
require_regex(GATT, r"clientCharCfgUUID\s*\}\s*,\s*GATT_PERMIT_READ\s*\|\s*GATT_PERMIT_WRITE", "CCCD must remain writable before encryption")
require_text(GATT, "GATT_Notification", "Samsung notify path must remain available")
require_text(ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsBle.kt", "byteArrayOf(0x03, 0x00)", "Android CCCD must remain 0x03 for Samsung")

# Android SMP имеет одного владельца состояния; GATT 5/15 — переход к pairing.
require_text(ANDROID_BLE, "private sealed interface SecurityState", "Android SMP needs one state owner")
require_text(ANDROID_BLE, "data class Pairing(", "Android SMP must represent Pairing explicitly")
require_text(ANDROID_BLE, "data class Resuming(", "blocked RX frame needs explicit resume state")
require_regex(ANDROID_BLE, r"startPairing\s*\(\s*PairingTrigger\.RX_WRITE\s*,\s*blocked\s*\)", "GATT 5/15 must enter RX pairing")
require_text(ANDROID_BLE, "if (wasSecurityHandshake) {", "SMP disconnect must be treated as an intermediate event")
require_regex(ANDROID_BLE, r"connectAttempts\s*=\s*0\s*\n\s*if\s*\(subscribed\)", "retry budget resets only after usable CCCD")
forbid_text(ANDROID_BLE, "PAIRING_DISCONNECT_STATUSES", "pairing correctness may not depend on disconnect status whitelist")
forbid_text(ANDROID_BLE, "POST_BOND_SETTLE_MS", "pairing may not depend on post-bond magic delay")
forbid_text(ANDROID_BLE, "securityPendingWrite", "blocked frame must live inside SecurityState")
forbid_text(ANDROID_BLE, "PAIRING_TIMEOUT_MS", "Android transport must not own a second connection deadline")
forbid_text(ANDROID_BLE, "pairingTimeout", "Android transport must not own a second timeout state")
require_text(ANDROID_SECURITY, "GATT_INSUFFICIENT_AUTHENTICATION = 5", "GATT 5 must be pairing-required")
require_text(ANDROID_SECURITY, "GATT_INSUFFICIENT_ENCRYPTION = 15", "GATT 15 must be pairing-required")
require_regex(ANDROID_SECURITY, r"requiresPairing\s*\(status\)\s*->\s*WriteDisposition\.PAIRING_REQUIRED", "security errors must not fall through to retry")

# iOS pairing опирается на события и epoch, а не на фиксированное число попыток.
require_text(IOS_BLE, "private sealed interface SecurityState", "iOS SMP needs one state owner")
require_regex(IOS_BLE, r"SecurityState\.Pairing\s*\(\s*blocked\.copyOf\(\)\s*\)", "iOS must preserve exact blocked RX frame")
require_text(IOS_BLE, "private var securityEpoch = 0L", "delayed iOS security work needs epoch identity")
require_text(IOS_BLE, "scheduleSecurityReconnect(didDisconnectPeripheral)", "SMP disconnect must preserve pairing work")
forbid_text(IOS_BLE, "PAIRING_WRITE_RETRIES", "iOS pairing may not fail after a fixed retry count")
forbid_text(IOS_BLE, "pairingRetryCount", "iOS pairing may not use retry count as security truth")
forbid_text(IOS_BLE, "looksLikeStaleBondError(error.localizedDescription)", "localized text may not decide bond validity")

# Ошибка SMP закрывает link, но не стирает все сохранённые bonds.
require_regex(PHY_TARGET, r"uint8\s+bond_fail\s*=\s*GAPBOND_FAIL_TERMINATE_LINK\s*;", "pairing failure must not erase bonds")
forbid_text(PHY_TARGET, "GAPBOND_FAIL_TERMINATE_ERASE_BONDS", "generic SMP failure must not erase bonds")
forbid_regex(PHY_TARGET, r"bond_pair_state_cb\s*\([^}]+GAPBondMgr_SetParameter", "pair callback must not mutate bond persistence")
require_text(PHY_APP, "#define DPLS_LINK_ENCRYPT_TIMEOUT_MS 60000u", "firmware plaintext budget must exceed mobile handshake")
forbid_text(PHY_APP, "DPLS_BOND_DESYNC", "application auth may not own BLE bond validity")
forbid_text(PHY_APP, "note_pre_auth_disconnect", "DPLS auth disconnects may not erase BLE bonds")
forbid_text(PHY_APP, "erase_bonds_and_drop_link", "plaintext timeout may not erase bonds")

# PHY6252: один OSAL dispatcher, отдельная safety policy и простой flash facade.
require_text(PHY_TARGET, "uint16 SimpleBLEPeripheral_ProcessEvent", "PHY target must expose one OSAL event dispatcher")
require_text(SAFETY, "dpls_safety_required_return", "dangerous-mode policy must stay in safety reducer")
for forbidden in ("GAPRole_", "osal_", "GATT_"):
    forbid_text(SAFETY, forbidden, f"safety reducer may not depend on transport/runtime API: {forbidden}")
require_text(PHY_APP_H, "#define DPLS_PHY6252_STORAGE_EVT 0x1000", "storage needs a separate OSAL event")
require_text(PHY_STORAGE, "dpls_phy6252_flash_work_pending", "PHY shell needs one storage facade")
require_text(PHY_STORAGE, "dpls_phy6252_snv_pending() || dpls_phy6252_storage_pending()", "flash pending must derive from real queues")
require_text(PHY_STORAGE, "if (dpls_phy6252_link_active()) return false;", "physical flash work must refuse an active link")
require_text(PHY_SNV, "return deferred.pending && dpls_phy6252_link_active();", "SNV disconnect request must derive from staged bytes and live link")
require_text(PHY_APP, "static dpls_event_t journal_pending_events", "journal needs RAM write-behind")
require_regex(PHY_APP, r"void\s+dpls_phy6252_process_storage\s*\([^)]*\)\s*\{[^}]*if\s*\(\s*dpls_phy6252_link_active\(\)\s*\|\|\s*journal_pending_event_count\s*==\s*0u\s*\)\s*return\s*;", "storage service must refuse an active BLE link")
require_regex(PHY_APP, r"static\s+bool\s+journal_flush_one_block\s*\([^)]*\)\s*\{[^}]*connection_handle\s*!=\s*INVALID_CONNHANDLE[^}]*return\s+false\s*;", "physical journal commit needs a second active-link guard")
require_text(PHY_TARGET, "if (events & DPLS_PHY6252_STORAGE_EVT)", "target must service deferred storage event")
forbid_text(PHY_TARGET, "dpls_phy6252_snv_pending()", "target must use the storage facade")
forbid_text(PHY_TARGET, "dpls_phy6252_storage_pending()", "target must use the storage facade")
for legacy in (
    ROOT / "firmware/include/dpls_storage_actor.h",
    ROOT / "firmware/src/dpls_storage_actor.c",
    ROOT / "firmware/tests/test_storage_actor.c",
):
    if legacy.exists():
        fail(legacy, "legacy storage actor must not return")
forbid_text(PHY_APP, "journal_flush_snv", "tick-time journal flash path must not return")
forbid_text(PHY_APP, "journal_snv_dirty", "old dirty-page ownership is forbidden")
forbid_text(PHY_APP, "journal_pending_block", "old single pending-page buffer is forbidden")
require_text(PHY_APP, "static uint8_t snv_write_bounded", "SNV writes need bounded watchdog scope")
require_text(PHY_APP, "watchdog_config(WDG_8S)", "blocking SNV scope must widen watchdog")
require_text(PHY_APP, "watchdog_config(WDG_2S)", "normal watchdog must be restored")

# TX: notify ограничен таймером, indication завершается только реальным ATT CFM.
require_text(PHY_APP, "#define DPLS_TX_CONFIRM_TIMEOUT_MS 2000u", "indication needs a bounded confirmation timeout")
require_text(PHY_TARGET, "#define DPLS_TICK_MS 1000u", "connected scheduler must check deadlines at <=1 s cadence")
require_regex(PHY_APP, r"static\s+void\s+tick_tx\s*\([^)]*\)\s*\{[^}]*tx\.in_flight[^}]*dpls_gatt_needs_confirmation\s*\([^)]*\)[^}]*now\s*-\s*tx\.in_flight_since_ms[^}]*DPLS_TX_CONFIRM_TIMEOUT_MS[^}]*GAPRole_TerminateConnection\s*\(\s*\)", "indication timeout must terminate the unconfirmed link")
forbid_regex(PHY_APP, r"static\s+void\s+tick_tx\s*\([^)]*\)\s*\{[^}]*DPLS_TX_CONFIRM_TIMEOUT_MS[^}]*tx_complete_head\s*\(\s*\)", "indication timeout must not mark an unconfirmed frame delivered")
forbid_regex(PHY_APP, r"static\s+void\s+tick_tx\s*\([^)]*\)\s*\{[^}]*dpls_phy6252_tx_confirmed\s*\(", "TX timeout must never fabricate TX_CONFIRMED")
require_regex(PHY_APP, r"osal_start_timerEx\s*\(\s*task_id\s*,\s*DPLS_PHY6252_TX_EVT\s*,\s*DPLS_TX_NOTIFY_PACE_MS", "notification pacing must have its own OSAL timer")
require_regex(PHY_APP, r"if\s*\(\s*rc\s*==\s*SUCCESS\s*\)\s*\{[^}]*tx\.in_flight\s*=\s*true\s*;[^}]*tx\.in_flight_since_ms\s*=\s*now_ms\(\)\s*;", "one ATT PDU must be marked in-flight with a timestamp")
require_text(PHY_APP, "static struct tc_hmac_state_struct hmac;", "HMAC must stay off 1 KiB OSAL stack")
require_regex(PHY_APP_H, r"bool\s+dpls_phy6252_tx_idle\s*\(\s*void\s*\)\s*;", "target needs an explicit TX-drained predicate before flash disconnect")
require_regex(PHY_TARGET, r"dpls_phy6252_flash_disconnect_requested\s*\(\s*\)\s*&&\s*dpls_phy6252_tx_idle\s*\(\s*\)", "flash disconnect must wait for TX drain")

# Симулятор сохраняет порядок turns, на котором уже ловились реальные регрессии.
require_text(ROOT / "firmware/sim/dpls_sim_transport.c", "pace_ms = dpls_sim_transport_cccd_notify(transport)", "simulator must preserve ATT pacing")
require_regex(ROOT / "firmware/sim/dpls_sim_board.c", r"phy6252_emu_tick\s*\([^;]+;\s*dpls_sim_board_process_tx\s*\(", "simulator timer/TX turns must remain separate")
if (ROOT / "firmware/phy6252_emu").exists():
    fail(ROOT / "firmware/phy6252_emu", "standalone PHY6252 emulator is forbidden; production HEX belongs to Firmverse")

# Ответ на неверный пароль должен попасть в TX раньше записи audit event.
# RX turn может лишь проверить, пора ли безопасно закрыть link ради flash.
require_regex(SERVER, r"send_auth_result\s*\(\s*s\s*,\s*f->sequence\s*,\s*DPLS_AUTH_DENIED\s*,\s*0\s*\)\s*;\s*\(void\)\s*dpls_server_log\s*\(\s*s\s*,\s*EVT_AUTH_FAILURE", "AUTH_RESULT must be queued before AUTH_FAILURE journal append")
require_regex(PHY_TARGET, r"dpls_phy6252_process_rx\s*\(\s*\)\s*;\s*disconnect_for_flash_if_ready\s*\(\s*\)\s*;\s*schedule_led_if_needed\s*\(\s*\)\s*;\s*return\s+events\s*\^\s*DPLS_PHY6252_RX_EVT", "RX turn may only check connection quiescence after domain RX")
forbid_regex(PHY_TARGET, r"if\s*\(events\s*&\s*DPLS_PHY6252_RX_EVT\s*\)\s*\{[^}]*dpls_phy6252_process_tx\s*\(", "RX turn must not pump TX")
forbid_regex(PHY_TARGET, r"if\s*\(events\s*&\s*DPLS_PHY6252_RX_EVT\s*\)\s*\{[^}]*dpls_phy6252_tx_confirmed\s*\(", "RX turn must not fabricate/consume ATT confirmation")
forbid_text(PHY_TARGET, "~DPLS_PHY6252_TX_EVT", "RX handler may not clear TX event")
require_regex(PHY_TARGET, r"uint8\s+update_enabled\s*=\s*FALSE", "slave conn-param update must stay disabled")

# Прямое присваивание StateFlow обязано повторно наложить lifecycle projection.
for number, line in enumerate(source(CLIENT).splitlines(), start=1):
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
print("  mobile lifecycle: BLE driver -> ConnectionActor -> ConnectionMachine -> DeviceSession")
print("  lifecycle mutations: semantic ConnectionEvent only")
print("  transaction identity: Frame.sequence + generation")
print("  GATT security boundary: plaintext CCCD -> encrypted RX")
print("  Android/iOS SMP: explicit state + event/epoch transitions")
print("  PHY app: one OSAL event dispatcher")
print("  safety: dpls_safety reducer owns dangerous mode")
print("  storage: real queues -> one flash facade, no actor state")
print("  TX indication deadline != TX confirmation")
