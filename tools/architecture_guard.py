#!/usr/bin/env python3
"""RC8 ownership invariants.

Checks facts that must remain singular: one source graph, one link owner, one
logical mode owner, one flash writer, one request in flight and fail-safe output
admission.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PHY = ROOT / "firmware/phy6252"
TARGET = ROOT / "firmware/targets/phy6252/source/dplsBLEPeripheral.c"
MAKEFILE = ROOT / "firmware/targets/phy6252/Makefile"
CPROJECT = ROOT / "firmware/targets/phy6252/test-dpls.cproject.yml"
SERVER = ROOT / "firmware/src/dpls_server.c"
SERVER_H = ROOT / "firmware/include/dpls_server.h"
SAFETY = ROOT / "firmware/src/dpls_safety.c"
SAFETY_H = ROOT / "firmware/include/dpls_safety.h"
GATT = PHY / "dpls_gatt_service.c"
IDENTITY = PHY / "dpls_ble_identity.c"
RUNTIME = PHY / "dpls_phy6252_runtime.c"
STORAGE = PHY / "dpls_phy6252_storage.c"
TRANSPORT = PHY / "dpls_phy6252_transport.c"
OUTPUTS = PHY / "dpls_phy6252_outputs.c"
AUTH = PHY / "dpls_phy6252_auth.c"
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
MACHINE = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/ConnectionMachine.kt"
SESSION = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/DeviceSession.kt"
TRANSPORT_KT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsTransport.kt"

EXPECTED_PHY = {
    "dpls_gatt_service.c", "dpls_ble_identity.c", "dpls_phy6252_auth.c",
    "dpls_phy6252_measurements.c", "dpls_phy6252_outputs.c",
    "dpls_phy6252_runtime.c", "dpls_phy6252_storage.c",
    "dpls_phy6252_supervisor.c", "dpls_phy6252_transport.c",
}
EXPECTED_CORE = {
    "dpls_protocol.c", "dpls_server.c", "dpls_safety.c", "dpls_led.c",
    "dpls_calib.c", "dpls_durable_settings.c",
}
REMOVED = {
    "dpls_phy6252_app.c", "dpls_phy6252_app.h",
    "dpls_phy6252_snv_guard.c", "dpls_phy6252_snv_guard.h",
    "dpls_phy6252_storage_ble.c", "dpls_phy6252_storage_ble.h",
}
errors: list[str] = []


def text(path: Path) -> str: return path.read_text(encoding="utf-8")
def fail(path: Path | str, message: str) -> None:
    name = str(path if isinstance(path, str) else path.relative_to(ROOT))
    errors.append(f"{name}: {message}")
def need(path: Path, token: str, message: str) -> None:
    if token not in text(path): fail(path, message)
def forbid(path: Path, token: str, message: str) -> None:
    if token in text(path): fail(path, message)
def section(path: Path, start: str, end: str) -> str:
    src = text(path)
    try: return src.split(start, 1)[1].split(end, 1)[0]
    except IndexError:
        fail(path, f"cannot isolate {start!r}..{end!r}")
        return ""

# One production source graph, identical first-party behavior for GCC and AC6.
for name in REMOVED:
    if (PHY / name).exists(): fail(PHY / name, "removed runtime/duplicate returned")
make = text(MAKEFILE); cproject = text(CPROJECT)
make_phy = set(re.findall(r"\$\(FW\)/phy6252/(dpls[^\s\\]+\.c)", make))
ac6_phy = set(re.findall(r"\.\./\.\./phy6252/(dpls[^\s]+\.c)", cproject))
make_core = set(re.findall(r"\$\(FW\)/src/(dpls[^\s\\]+\.c)", make))
ac6_core = set(re.findall(r"\.\./\.\./src/(dpls[^\s]+\.c)", cproject))
for path, actual, expected in (
    (MAKEFILE, make_phy, EXPECTED_PHY), (CPROJECT, ac6_phy, EXPECTED_PHY),
    (MAKEFILE, make_core, EXPECTED_CORE), (CPROJECT, ac6_core, EXPECTED_CORE),
):
    if actual != expected: fail(path, f"source set mismatch: {sorted(actual ^ expected)}")

# Cognitive budget is a release invariant.
phy_lines = 0
for path in sorted(PHY.glob("*.c")):
    lines = len(text(path).splitlines()); phy_lines += lines
    if lines > 600: fail(path, f"{lines} lines exceeds 600-line module budget")
if phy_lines > 2000: fail("firmware/phy6252", f"adapter is {phy_lines} lines; budget 2000")
production_paths = list(PHY.glob("*.c")) + list((ROOT / "firmware/src").glob("*.c")) + [TARGET]
production_lines = sum(len(text(path).splitlines()) for path in production_paths)
if production_lines > 5000: fail("firmware", f"production C is {production_lines} lines; budget 5000")

# Link fact belongs only to transport. Target/runtime may query it; nobody shadows it.
need(TRANSPORT, "static uint16 connection_handle", "transport must own the physical link fact")
forbid(STORAGE, "link_active", "storage must not shadow BLE link state")
forbid(TARGET, "link_up", "target must not shadow BLE link state")
need(TARGET, "dpls_phy6252_runtime_link_active()", "target must query runtime link fact")

# Logical mode belongs only to dpls_safety_t. GPIO adapter is a stateless actuator.
forbid(OUTPUTS, "hardware_mode", "outputs must not copy logical mode")
forbid(OUTPUTS, "dpls_phy6252_outputs_mode", "second mode getter returned")
need(RUNTIME, "server.safety.mode", "runtime must consume canonical safety mode")

# One request at a time, bounded TX, and capacity reserved before ATT accepts RX.
need(TRANSPORT, "#define DPLS_RX_QUEUE_DEPTH 1u", "RX must serialize application transactions")
need(TRANSPORT, "#define DPLS_TX_QUEUE_DEPTH 2u", "TX must allow in-flight + one queued response")
need(TRANSPORT, "(uint8)(rx.count + tx.count) >= DPLS_TX_QUEUE_DEPTH",
     "accepted RX must reserve response capacity")
need(RUNTIME, "static uint8 receive_frame", "runtime must own GATT admission boundary")
need(RUNTIME, "server.critical_fault || dpls_phy6252_storage_critical_pending()",
     "quiescing connection must reject new application work")
need(RUNTIME, "dpls_gatt_add_service(receive_frame)", "GATT must enter through runtime admission")

# One physical SNV writer. Offline permission is a current fact, never stored.
for path in list(PHY.glob("*.c")) + list((ROOT / "firmware/src").glob("*.c")):
    if "osal_snv_write" in text(path) and path != STORAGE:
        fail(path, "physical SNV write outside storage owner")
need(STORAGE, "dpls_phy6252_storage_process_one(bool radio_offline)", "explicit offline write boundary missing")
need(STORAGE, "if (!radio_offline) return false;", "storage must refuse write without offline permission")
need(STORAGE, "dpls_phy6252_storage_critical_pending", "critical dirty fact missing")
need(RUNTIME, "dpls_phy6252_storage_critical_pending()", "runtime must own controlled disconnect decision")
need(RUNTIME, "dpls_phy6252_transport_tx_idle()", "persistence must wait for TX drain")
need(STORAGE, "static uint32_t journal_timestamp[DPLS_EVENT_CAPACITY]", "compact RAM journal missing")
forbid(STORAGE, "journal_records[DPLS_EVENT_CAPACITY]", "flash record representation leaked into RAM")

# Dangerous GPIO requires a successfully acquired sleep resource before any pin is energized.
need(OUTPUTS, "if (!control_sleep_guard(true)) return false;", "dangerous mode must require sleep lock")
lock_pos = text(OUTPUTS).find("if (!control_sleep_guard(true)) return false;")
first_dangerous_write = text(OUTPUTS).find("hal_gpio_write(DPLS_PIN_ISO_T, 1)")
if lock_pos < 0 or first_dangerous_write < 0 or lock_pos > first_dangerous_write:
    fail(OUTPUTS, "dangerous GPIO may be energized before sleep lock")

# Bond erase exists only behind physical factory reset; radio failures never infer key state.
for path in PHY.glob("*.c"):
    for token in ("DPLS_BOND_DESYNC", "pre_auth_disconnect", "bond_erase_requested",
                  "dpls_ble_identity_reset_bonding_keys"):
        if token in text(path): fail(path, f"forbidden bond heuristic returned: {token}")
erases = sum(text(path).count("GAPBOND_ERASE_ALLBONDS") for path in PHY.glob("*.c"))
if erases != 1 or "GAPBOND_ERASE_ALLBONDS" not in text(TRANSPORT):
    fail(TRANSPORT, "bond erase must exist exactly once in transport factory-reset path")
need(RUNTIME, "dpls_phy6252_transport_factory_forget_bonds()", "factory reset must own bond erase call")
forbid(TRANSPORT_KT, 'text.contains("encryption timed out")', "generic timeout cannot mean stale bond")

# Boot identity cannot touch controller lifecycle too early.
prepare = section(IDENTITY, "void dpls_ble_identity_prepare", "void dpls_ble_identity_on_stack_started")
for token in ("LL_ENC_", "osal_snv_", "HCI_"):
    if token in prepare: fail(IDENTITY, f"early identity path contains {token}")
need(AUTH, "LL_ENC_GenerateTrueRandNum", "TRNG must live in post-link auth adapter")
started = section(TARGET, "case GAPROLE_STARTED:", "case GAPROLE_CONNECTED:")
if "enable_advertising();" not in started: fail(TARGET, "started state must always advertise")
if started.find("dpls_ble_identity_on_stack_started();") > started.find("enable_advertising();"):
    fail(TARGET, "controller identity must be set before advertising")

# Safety policy is pure and fail-safe before entry and while dangerous mode is active.
need(SAFETY_H, "bool measurements_ready;", "measurement validity must be a safety input fact")
need(SAFETY, "dpls_safety_admission_reason", "dangerous-mode admission policy missing")
need(SAFETY, "DPLS_SAFETY_RETURN_MEASUREMENT_LOST", "measurement-loss fail-safe missing")
need(SERVER, "safety_measurements_ready", "server must derive readiness from measurement validity")
for token in ("GAPRole_", "osal_", "GATT_"):
    forbid(SAFETY, token, f"pure safety depends on runtime API: {token}")
forbid(SERVER, "setup_disconnect_deadline_ms", "timer-owned persistence disconnect returned")
forbid(SERVER_H, "setup_disconnect_deadline_ms", "dead disconnect timer state returned")
for token in ("osal_snv_write", "GAPBOND_ERASE_ALLBONDS", "GAPRole_TerminateConnection"):
    forbid(SERVER, token, f"domain server owns physical side effect {token}")

# GATT security and real ATT completion remain platform facts.
need(GATT, "GATT_PERMIT_ENCRYPT_WRITE", "RX characteristic must require encryption")
need(GATT, "GATT_Notification", "notify compatibility path missing")
need(TARGET, "ATT_HANDLE_VALUE_CFM", "real indication confirmation missing")

# Mobile lifecycle has one mutable owner and one reducer write path.
need(SESSION, "sealed interface DeviceSession", "DeviceSession lifecycle missing")
need(MACHINE, "fun reduce(state: DeviceSession, event: ConnectionEvent)", "connection reducer missing")
need(CLIENT, "private var session: DeviceSession = DeviceSession.Offline", "DplsClient must own lifecycle")
writes = [line.strip() for line in text(CLIENT).splitlines() if re.match(r"^session\s*=", line.strip())]
if writes != ["session = ConnectionMachine.reduce(session, event)"]:
    fail(CLIENT, f"unexpected lifecycle writes: {writes}")

if errors:
    print("Architecture guard failed:")
    for item in errors: print(f"  - {item}")
    raise SystemExit(1)
print("Architecture guard: PASS")
print(f"  first-party firmware: {production_lines} lines; PHY adapter: {phy_lines} lines")
print("  one link owner, one mode owner, one flash writer, one explicit bond erase")
print("  one RX transaction; reserved TX; quiescent durable boundary; fail-safe outputs")
