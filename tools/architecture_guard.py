#!/usr/bin/env python3
"""Небольшой набор архитектурных инвариантов Test-DPLS.

Guard проверяет только границы ownership и известные аппаратные запреты.
Поведение проверяют unit/E2E/Firmverse, а не коллекция хрупких regex'ов.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
MACHINE = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/ConnectionMachine.kt"
SESSION = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/DeviceSession.kt"
CONTROL = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsControlMessages.kt"
MESSAGES = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsMessages.kt"
ANDROID_BLE = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"
IOS_BLE = ROOT / "mobile/core/src/iosMain/kotlin/ru/bolid/testdpls/core/app/IosBleTransport.kt"
GATT = ROOT / "firmware/phy6252/dpls_gatt_service.c"
IDENTITY = ROOT / "firmware/phy6252/dpls_ble_identity.c"
PHY_APP = ROOT / "firmware/phy6252/dpls_phy6252_app.c"
PHY_TARGET = ROOT / "firmware/targets/phy6252/source/dplsBLEPeripheral.c"
PHY_STORAGE = ROOT / "firmware/phy6252/dpls_phy6252_storage.c"
PHY_SNV = ROOT / "firmware/phy6252/dpls_phy6252_snv_guard.c"
SERVER = ROOT / "firmware/src/dpls_server.c"
SAFETY = ROOT / "firmware/src/dpls_safety.c"

errors: list[str] = []


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def fail(path: Path, message: str) -> None:
    errors.append(f"{path.relative_to(ROOT)}: {message}")


def need(path: Path, token: str, message: str) -> None:
    if token not in text(path):
        fail(path, message)


def forbid(path: Path, token: str, message: str) -> None:
    if token in text(path):
        fail(path, message)


def section(path: Path, start: str, end: str) -> str:
    src = text(path)
    try:
        return src.split(start, 1)[1].split(end, 1)[0]
    except IndexError:
        fail(path, f"cannot isolate section {start!r}..{end!r}")
        return ""


# Mobile lifecycle: один mutable DeviceSession и один reducer.
for token in (
    "sealed interface DeviceSession",
    "data class Securing(",
    "data class Synchronizing(",
    "data class Online(",
):
    need(SESSION, token, f"missing lifecycle state: {token}")
for token in ("data class Commissioning(", "data class Authenticating("):
    forbid(SESSION, token, f"duplicate lifecycle state returned: {token}")
need(CLIENT, "private var session: DeviceSession = DeviceSession.Offline", "DplsClient must own lifecycle")
need(CLIENT, "session = ConnectionMachine.reduce(session, event)", "lifecycle must mutate through reducer")
writes = [line.strip() for line in text(CLIENT).splitlines() if re.match(r"^session\s*=", line.strip())]
if writes != ["session = ConnectionMachine.reduce(session, event)"]:
    fail(CLIENT, f"unexpected lifecycle writes: {writes}")
need(MACHINE, "fun reduce(state: DeviceSession, event: ConnectionEvent)", "connection reducer missing")
for actor in (
    ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/ConnectionActor.kt",
    ROOT / "firmware/include/dpls_storage_actor.h",
    ROOT / "firmware/src/dpls_storage_actor.c",
    ROOT / "firmware/tests/test_storage_actor.c",
):
    if actor.exists():
        fail(actor, "removed actor must not return")

# Protocol v2: один layout и Frame.sequence как transaction id.
for path in (ROOT / "mobile").rglob("*.kt"):
    if re.search(r"\bcommandId\b", text(path)):
        fail(path, "legacy commandId is forbidden")
forbid(CONTROL, "@Deprecated", "protocol compatibility annotations are forbidden")
need(CONTROL, "if (raw.size != 4) return null", "COMMAND_RESULT must have exact v2 layout")
need(CONTROL, "if (raw.size != 1) return null", "SETTINGS_RESULT must have exact v2 layout")
need(CONTROL, "if (raw.size != 11) return null", "AUTH_RESULT must have exact layout")
need(MESSAGES, "if (raw.size != 25) return null", "STATE_REPORT must have exact layout")
forbid(MESSAGES, "legacyLine", "legacy STATE_REPORT fallback is forbidden")

# common runtime/wire не зависят от UI или platform API.
for root in (ROOT / "mobile/runtime/src/commonMain", ROOT / "mobile/wire/src/commonMain"):
    for path in root.rglob("*.kt"):
        for token in ("android.", "androidx.compose", "platform.CoreBluetooth", ".core.domain.", ".core.app."):
            if token in text(path):
                fail(path, f"dependency leak: {token}")

# BLE security boundary и SMP ownership.
need(GATT, "GATT_PERMIT_ENCRYPT_WRITE", "RX must remain encrypted")
need(GATT, "GATT_Notification", "Samsung notify path must remain available")
for path in (ANDROID_BLE, IOS_BLE):
    need(path, "private sealed interface SecurityState", "platform SMP needs one explicit state owner")
for token in ("PAIRING_DISCONNECT_STATUSES", "POST_BOND_SETTLE_MS", "PAIRING_TIMEOUT_MS", "pairingTimeout"):
    forbid(ANDROID_BLE, token, f"Android pairing must not depend on {token}")
for token in ("PAIRING_WRITE_RETRIES", "pairingRetryCount"):
    forbid(IOS_BLE, token, f"iOS pairing must not depend on {token}")

# BLE identity: никакого project factory sector и advertising не зависит от identity readiness.
need(IDENTITY, "HCI_EXT_SetBDADDRCmd", "controller address must be configured before GAP start")
for token in ("DPLS_FACTORY_IDENTITY", "hal_flash_read(", "dpls_ble_identity_is_ready", "dpls_ble_identity_is_provisioned"):
    forbid(IDENTITY, token, f"removed factory/identity gate returned: {token}")
started = section(PHY_TARGET, "case GAPROLE_STARTED:", "case GAPROLE_CONNECTED:")
if "enable_advertising();" not in started:
    fail(PHY_TARGET, "GAPROLE_STARTED must enable advertising")
if "if (" in started and "identity" in started.lower():
    fail(PHY_TARGET, "identity may not gate advertising")

# Boot journal: init может наполнить RAM queue, но не имеет права ставить storage event.
init = section(PHY_APP, "void dpls_phy6252_init", "void dpls_phy6252_connected")
if "DPLS_PHY6252_STORAGE_EVT" in init:
    fail(PHY_APP, "dpls_phy6252_init must not schedule storage")
start_event = section(PHY_TARGET, "if (events & SBP_START_DEVICE_EVT)", "if (events & DPLS_PHY6252_RX_EVT)")
for token in ("DPLS_PHY6252_STORAGE_EVT", "schedule_storage", "flash"):
    if token in start_event:
        fail(PHY_TARGET, f"GAP start handler must not know about storage: {token}")
need(PHY_TARGET, "GAPRole_StartDevice(&role_callbacks);", "GAP start missing")
need(PHY_TARGET, "if (!dpls_phy6252_link_active()) schedule_storage_if_needed();", "idle tick must own deferred storage scheduling")

# Flash: один facade, реальная очередь и запрет physical write при active link.
need(PHY_STORAGE, "dpls_phy6252_snv_pending() || dpls_phy6252_storage_pending()", "flash pending must derive from real queues")
need(PHY_STORAGE, "if (dpls_phy6252_link_active()) return false;", "flash facade must refuse active link")
need(PHY_SNV, "return deferred.pending && dpls_phy6252_link_active();", "SNV disconnect request must derive from live facts")
need(PHY_APP, "static dpls_event_t journal_pending_events", "journal needs RAM write-behind")
need(PHY_APP, "if (connection_handle != INVALID_CONNHANDLE) return false;", "journal physical write needs active-link guard")
for token in ("journal_snv_dirty", "journal_pending_block", "journal_flush_snv"):
    forbid(PHY_APP, token, f"old journal owner returned: {token}")

# Settings: только текущие durable slots.
for token in ("DPLS_LEGACY_SETTINGS_", "dpls_legacy_settings_t", "classify_legacy_settings", "DPLS_SETTINGS_EMPTY_MARKER"):
    forbid(PHY_APP, token, f"legacy settings path returned: {token}")
need(PHY_APP, "DPLS_SETTINGS_SLOT_A_SNV_ID 0x85u", "durable settings slot A missing")
need(PHY_APP, "DPLS_SETTINGS_SLOT_B_SNV_ID 0x86u", "durable settings slot B missing")

# Safety — pure owner of dangerous-mode policy.
need(SAFETY, "dpls_safety_required_return", "safety reducer missing")
for token in ("GAPRole_", "osal_", "GATT_"):
    forbid(SAFETY, token, f"safety may not depend on runtime API: {token}")

# TX: one in-flight ATT PDU, real confirmation, bounded timeout.
need(PHY_APP, "#define DPLS_TX_CONFIRM_TIMEOUT_MS 2000u", "TX confirmation timeout missing")
need(PHY_APP, "tx.in_flight = true", "TX must track one in-flight PDU")
need(PHY_APP, "dpls_gatt_needs_confirmation", "indication confirmation boundary missing")
need(PHY_APP, "GAPRole_TerminateConnection();", "unconfirmed indication must be able to terminate link")
need(PHY_TARGET, "ATT_HANDLE_VALUE_CFM", "target must consume real ATT confirmation")
need(PHY_TARGET, "dpls_phy6252_tx_idle()", "flash disconnect must wait for TX drain")
need(PHY_APP, "static struct tc_hmac_state_struct hmac;", "HMAC state must stay off 1 KiB stack")

# Auth response должен попасть в TX до audit append.
server = text(SERVER)
response = server.find("send_auth_result(s, f->sequence, DPLS_AUTH_DENIED, 0);")
audit = server.find("dpls_server_log(s, EVT_AUTH_FAILURE", response)
if response < 0 or audit < 0 or response > audit:
    fail(SERVER, "AUTH_RESULT must be queued before AUTH_FAILURE journal append")

# UI StateFlow не может обойти lifecycle projection.
for number, line in enumerate(text(CLIENT).splitlines(), start=1):
    stripped = line.strip()
    if "mutableState.value =" not in stripped:
        continue
    if "projectSession(" in stripped or stripped.startswith("private val mutableState"):
        continue
    fail(CLIENT, f"line {number}: UI replacement bypasses session projection")

if errors:
    print("Architecture guard failed:")
    for item in errors:
        print(f"  - {item}")
    raise SystemExit(1)

print("Architecture guard: OK")
print("  one mobile lifecycle owner, protocol v2 only")
print("  advertising precedes deferred persistence")
print("  one flash facade, no writes with active link")
print("  safety owns dangerous mode, TX uses real ATT confirmation")
