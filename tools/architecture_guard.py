#!/usr/bin/env python3
"""Fail CI on narrow repository-specific ownership and safety invariants."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
SESSION = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/DeviceSession.kt"
SEQUENCER = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/session/DplsSession.kt"
ANDROID_BLE = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"
ANDROID_SECURITY = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidGattSecurityPolicy.kt"
IOS_BLE = ROOT / "mobile/core/src/iosMain/kotlin/ru/bolid/testdpls/core/app/IosBleTransport.kt"
GATT = ROOT / "firmware/phy6252/dpls_gatt_service.c"
PHY_APP = ROOT / "firmware/phy6252/dpls_phy6252_app.c"
PHY_APP_H = ROOT / "firmware/phy6252/dpls_phy6252_app.h"
PHY_TARGET = ROOT / "firmware/targets/phy6252/source/dplsBLEPeripheral.c"

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
    if re.search(pattern, text(path), flags=re.MULTILINE | re.DOTALL):
        fail(path, message)


# Shared product lifecycle: one authoritative owner, UI only projects it.
require_text(SESSION, "sealed interface DeviceSession", "DeviceSession must own lifecycle state")
require_text(SESSION, "data class SessionChallenge", "challenge material must live in DeviceSession")
require_text(SESSION, "data class AuthSession", "authenticated wire material must live in DeviceSession")
require_text(SESSION, "data class Synchronizing(", "authentication must not imply verified identity")
require_text(SESSION, "data class Online(\n        val nodeId: NodeId,", "Online must require verified NodeId")
forbid_text(SESSION, "data class Online(\n        val nodeId: NodeId?", "Online may not contain unknown identity")
require_text(CLIENT, "private var session: DeviceSession", "controller must have one lifecycle owner")
require_text(CLIENT, "private fun projectSession", "UI lifecycle must be projected")
require_text(CLIENT, "phase = connectionPhase(ui)", "UI phase must derive from DeviceSession")
for stale_owner in ("DplsSessionRuntime", "wireSession", "runtimeSession", "selectedAddress"):
    forbid_text(CLIENT, stale_owner, f"second session/route owner is forbidden: {stale_owner}")
for ui_truth in ("state.phase", "state.authenticated", "state.credentialsReady"):
    forbid_text(CLIENT, ui_truth, f"controller must not branch on UI projection {ui_truth}")
for number, line in enumerate(text(CLIENT).splitlines(), start=1):
    if re.match(r"^\s*phase\s*=", line) and "phase = connectionPhase(ui)" not in line:
        fail(CLIENT, f"line {number}: lifecycle phase must be projected")
for field in ("sessionId", "sessionToken", "clientNonce", "deviceNonce", "authSalt", "authenticated"):
    forbid_regex(CLIENT, rf"^\s*private\s+(?:var|val)\s+{field}\b", f"independent lifecycle field forbidden: {field}")

# Wire identity: Frame.sequence is the only transaction id.
require_text(SEQUENCER, "class FrameSequencer", "wire helper must be sequence-only")
for secret in ("sessionId", "sessionToken", "clientNonce", "deviceNonce", "authSalt"):
    forbid_text(SEQUENCER, secret, f"FrameSequencer must not own {secret}")
legacy_command_id_paths = {"DplsControlMessages.kt", "DplsControlMessagesTest.kt"}
for path in (ROOT / "mobile").rglob("*.kt"):
    if path.name not in legacy_command_id_paths:
        forbid_regex(path, r"\bcommandId\b", "second transaction id commandId is forbidden")

# Delayed common work must carry identity, not rely on cancellation timing.
require_text(
    CLIENT,
    "if (generation == linkGeneration && operation?.sequence == sequence) action()",
    "operation timeout must check link epoch and request sequence",
)
for generation in ("linkGeneration", "scanGeneration", "logTimeoutGeneration"):
    require_text(CLIENT, generation, f"missing stale-work generation: {generation}")

# Runtime/wire dependency zones stay platform- and UI-free.
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

# GATT security boundary shared by both phones: subscribe plaintext, first RX
# protocol write encrypted. Never move the SMP trigger back to the CCCD.
require_text(
    GATT,
    "{{ATT_UUID_SIZE, (uint8 *)dpls_rx_uuid}, GATT_PERMIT_WRITE | GATT_PERMIT_ENCRYPT_WRITE",
    "RX must remain the encrypted security boundary",
)
require_text(
    GATT,
    "{{ATT_BT_UUID_SIZE, clientCharCfgUUID}, GATT_PERMIT_READ | GATT_PERMIT_WRITE",
    "CCCD must remain writable before encryption",
)
require_text(GATT, "GATT_Notification", "Samsung notify path must remain available")
require_text(
    ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsBle.kt",
    "byteArrayOf(0x03, 0x00)",
    "Android CCCD must remain 0x03 for Samsung",
)

# Android: one security state owner. GATT 5/15 is a transition into Pairing,
# never an ordinary transport error. The blocked frame survives SMP disconnect.
require_text(
    ANDROID_BLE,
    "BluetoothDevice.PHY_LE_1M_MASK,\n            handler,",
    "Android callbacks must stay on the main Handler",
)
require_text(ANDROID_BLE, "private sealed interface SecurityState", "Android SMP needs one state owner")
require_text(ANDROID_BLE, "data class Pairing(", "Android SMP must represent Pairing explicitly")
require_text(ANDROID_BLE, "data class Resuming(", "blocked RX frame needs explicit resume state")
require_text(ANDROID_BLE, "startPairing(PairingTrigger.RX_WRITE, blocked)", "GATT 5/15 must enter RX pairing")
require_text(
    ANDROID_BLE,
    "if (wasSecurityHandshake) {",
    "SMP disconnect must be treated as intermediate, independent of status code",
)
require_text(
    ANDROID_BLE,
    "connectAttempts = 0\n                if (subscribed)",
    "link retry budget must reset only after a usable CCCD subscription",
)
forbid_text(ANDROID_BLE, "PAIRING_DISCONNECT_STATUSES", "pairing correctness may not depend on disconnect status whitelist")
forbid_text(ANDROID_BLE, "POST_BOND_SETTLE_MS", "pairing may not depend on post-bond magic delay")
forbid_text(ANDROID_BLE, "securityPendingWrite", "blocked frame must live inside SecurityState")
require_text(ANDROID_SECURITY, "GATT_INSUFFICIENT_AUTHENTICATION = 5", "GATT 5 must be pairing-required")
require_text(ANDROID_SECURITY, "GATT_INSUFFICIENT_ENCRYPTION = 15", "GATT 15 must be pairing-required")
require_text(
    ANDROID_SECURITY,
    "requiresPairing(status) -> WriteDisposition.PAIRING_REQUIRED",
    "security errors must not fall through to backpressure retry",
)

# iOS: no human-vs-timer race. Repeated protected-write 5/15 remains Pairing;
# only explicit CoreBluetooth stale-key errors are treated as stale bond.
require_text(IOS_BLE, "private sealed interface SecurityState", "iOS SMP needs one state owner")
require_text(IOS_BLE, "SecurityState.Pairing(blocked.copyOf())", "iOS must preserve exact blocked RX frame")
require_text(IOS_BLE, "private var securityEpoch = 0L", "delayed iOS security work needs epoch identity")
require_text(IOS_BLE, "scheduleSecurityReconnect(didDisconnectPeripheral)", "SMP disconnect must preserve pairing work")
forbid_text(IOS_BLE, "PAIRING_WRITE_RETRIES", "iOS pairing may not fail after a fixed retry count")
forbid_text(IOS_BLE, "pairingRetryCount", "iOS pairing may not use retry count as security truth")
forbid_text(IOS_BLE, "looksLikeStaleBondError(error.localizedDescription)", "localized text may not decide bond validity")

# PHY6252 GAP bond persistence: a generic pairing failure may terminate that
# link, but must never erase all stored bonds. Global erase is reserved for the
# physical reset/uncommissioned bootstrap paths in app code.
require_text(PHY_TARGET, "uint8 bond_fail = GAPBOND_FAIL_TERMINATE_LINK;", "pairing failure must not erase bonds")
forbid_text(PHY_TARGET, "GAPBOND_FAIL_TERMINATE_ERASE_BONDS", "generic SMP failure must not erase bonds")
forbid_regex(
    PHY_TARGET,
    r"bond_pair_state_cb\([^}]+GAPBondMgr_SetParameter",
    "pair callback must not mutate bond persistence",
)

# Firmware plaintext timeout is only an ACL leak guard. It must be longer than
# the 45 s mobile handshake and may not infer bond staleness from DPLS auth.
require_text(PHY_APP, "#define DPLS_LINK_ENCRYPT_TIMEOUT_MS 60000u", "firmware pairing budget must exceed mobile 45 s")
forbid_text(PHY_APP, "DPLS_BOND_DESYNC", "application auth may not own BLE bond validity")
forbid_text(PHY_APP, "note_pre_auth_disconnect", "DPLS auth disconnects may not erase BLE bonds")
forbid_text(PHY_APP, "erase_bonds_and_drop_link", "plaintext timeout may not erase bonds")
require_text(
    PHY_APP,
    "(void)GAPRole_TerminateConnection();",
    "abandoned plaintext ACL may be terminated without mutating keys",
)

# Journal persistence: append is RAM-only while connected. Physical SNV writes
# happen only from a dedicated storage event after connection_handle is invalid,
# one block per OSAL turn. The old tick-time dirty-page flushing is forbidden.
require_text(PHY_APP_H, "#define DPLS_PHY6252_STORAGE_EVT 0x1000", "storage needs a separate OSAL event")
require_text(PHY_APP, "static dpls_event_t journal_pending_events", "journal needs RAM write-behind")
require_text(
    PHY_APP,
    "if (connection_handle != INVALID_CONNHANDLE || journal_pending_event_count == 0u) return;",
    "journal SNV service must refuse active BLE links",
)
require_text(PHY_APP, "static bool journal_flush_one_block(void)", "storage event must commit one block at a time")
require_text(PHY_TARGET, "if (events & DPLS_PHY6252_STORAGE_EVT)", "target must service deferred storage event")
forbid_text(PHY_APP, "journal_flush_snv", "tick-time journal flash path must not return")
forbid_text(PHY_APP, "journal_snv_dirty", "old dirty-page ownership is forbidden")
forbid_text(PHY_APP, "journal_pending_block", "old single pending-page buffer is forbidden")

# Blocking non-journal settings writes are still synchronous by protocol, so at
# least bound the watchdog ownership around the one blocking resource. They are
# not allowed to reintroduce direct journal writes on connected ticks.
require_text(PHY_APP, "static uint8_t snv_write_bounded", "SNV writes need bounded watchdog scope")
require_text(PHY_APP, "watchdog_config(WDG_8S)", "blocking SNV scope must widen watchdog")
require_text(PHY_APP, "watchdog_config(WDG_2S)", "normal watchdog must be restored")

# TX semantics: ATT confirmation and timeout are different events. Notifications
# are released by a pacing timer; indication timeout drops/recoveries explicitly.
require_text(PHY_APP, "DPLS TX timeout", "indication timeout needs explicit failure semantics")
require_text(
    PHY_APP,
    "tx_complete_head();\n    }\n    dpls_phy6252_process_tx();",
    "TX timeout must recover queue directly, not fabricate confirmation",
)
forbid_text(
    PHY_APP,
    "DPLS_TX_CONFIRM_TIMEOUT_MS) {\n        dpls_phy6252_tx_confirmed();",
    "TX timeout must never call TX_CONFIRMED",
)
require_text(
    PHY_APP,
    "osal_start_timerEx(task_id, DPLS_PHY6252_TX_EVT, DPLS_TX_NOTIFY_PACE_MS)",
    "notification pacing must have its own OSAL timer",
)
require_text(
    PHY_APP,
    "tx.in_flight = true;\n        tx.in_flight_since_ms = now_ms();",
    "one ATT PDU must remain in flight",
)
require_text(PHY_APP, "static struct tc_hmac_state_struct hmac;", "HMAC must stay off 1 KiB OSAL stack")

# Existing target/product invariants that previously caught real regressions.
require_text(
    ROOT / "firmware/sim/dpls_sim_transport.c",
    "pace_ms = dpls_sim_transport_cccd_notify(transport)",
    "simulator must preserve ATT pacing",
)
require_text(
    ROOT / "firmware/sim/dpls_sim_board.c",
    "phy6252_emu_tick(&board->radio, board->now_ms);\n    dpls_sim_board_process_tx(board);",
    "simulator timer/TX turns must remain separate",
)
if (ROOT / "firmware/phy6252_emu").exists():
    fail(ROOT / "firmware/phy6252_emu", "standalone PHY6252 emulator is forbidden; production HEX belongs to Firmverse")
require_text(
    ROOT / "firmware/src/dpls_server.c",
    "send_auth_result(s, f->sequence, DPLS_AUTH_DENIED, 0);\n        dpls_server_log(s, EVT_AUTH_FAILURE",
    "AUTH_RESULT must be queued before AUTH_FAILURE journal append",
)
require_text(
    PHY_TARGET,
    "dpls_phy6252_process_rx();\n        schedule_led_if_needed();\n        return events ^ DPLS_PHY6252_RX_EVT;",
    "RX turn must not pump/clear TX",
)
forbid_text(PHY_TARGET, "~DPLS_PHY6252_TX_EVT", "RX handler may not clear TX event")
require_text(PHY_TARGET, "uint8 update_enabled = FALSE", "slave conn-param update must stay disabled")

# Every direct StateFlow replacement must visibly re-apply lifecycle projection.
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
print("  product lifecycle owner: DeviceSession")
print("  transaction identity: Frame.sequence + generation")
print("  GATT security boundary: plaintext CCCD -> encrypted RX")
print("  Android SMP: single SecurityState, event-driven")
print("  iOS SMP: no fixed retry deadline, epoch-guarded")
print("  PHY6252 bond failures: terminate link, never erase all bonds")
print("  journal: RAM write-behind, SNV only after disconnect")
print("  TX timeout != TX confirmation")
