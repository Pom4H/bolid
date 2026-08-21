#!/usr/bin/env python3
"""RC8 architecture invariants.

The guard protects ownership, bounded queues and code size. If an invariant needs
complex pattern matching to explain, the production architecture is already too clever.
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
SAFETY = ROOT / "firmware/src/dpls_safety.c"
GATT = PHY / "dpls_gatt_service.c"
IDENTITY = PHY / "dpls_ble_identity.c"
RUNTIME = PHY / "dpls_phy6252_runtime.c"
STORAGE = PHY / "dpls_phy6252_storage.c"
TRANSPORT = PHY / "dpls_phy6252_transport.c"
AUTH = PHY / "dpls_phy6252_auth.c"
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
MACHINE = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/ConnectionMachine.kt"
SESSION = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/DeviceSession.kt"
TRANSPORT_KT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsTransport.kt"

EXPECTED_PHY = {
    "dpls_gatt_service.c",
    "dpls_ble_identity.c",
    "dpls_phy6252_auth.c",
    "dpls_phy6252_measurements.c",
    "dpls_phy6252_outputs.c",
    "dpls_phy6252_runtime.c",
    "dpls_phy6252_storage.c",
    "dpls_phy6252_supervisor.c",
    "dpls_phy6252_transport.c",
}
EXPECTED_CORE = {
    "dpls_protocol.c",
    "dpls_server.c",
    "dpls_safety.c",
    "dpls_led.c",
    "dpls_calib.c",
    "dpls_durable_settings.c",
}
REMOVED = {
    "dpls_phy6252_app.c",
    "dpls_phy6252_app.h",
    "dpls_phy6252_snv_guard.c",
    "dpls_phy6252_snv_guard.h",
    "dpls_phy6252_storage_ble.c",
    "dpls_phy6252_storage_ble.h",
}

errors: list[str] = []


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def fail(path: Path | str, message: str) -> None:
    name = str(path if isinstance(path, str) else path.relative_to(ROOT))
    errors.append(f"{name}: {message}")


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
        fail(path, f"cannot isolate {start!r}..{end!r}")
        return ""


# 1. There is exactly one production PHY6252 architecture.
for name in REMOVED:
    if (PHY / name).exists():
        fail(PHY / name, "legacy/duplicate runtime must not exist")

make = text(MAKEFILE)
cproject = text(CPROJECT)
make_phy = set(re.findall(r"\$\(FW\)/phy6252/(dpls[^\s\\]+\.c)", make))
ac6_phy = set(re.findall(r"\.\./\.\./phy6252/(dpls[^\s]+\.c)", cproject))
make_core = set(re.findall(r"\$\(FW\)/src/(dpls[^\s\\]+\.c)", make))
ac6_core = set(re.findall(r"\.\./\.\./src/(dpls[^\s]+\.c)", cproject))
if make_phy != EXPECTED_PHY:
    fail(MAKEFILE, f"PHY source set mismatch: {sorted(make_phy ^ EXPECTED_PHY)}")
if ac6_phy != EXPECTED_PHY:
    fail(CPROJECT, f"PHY source set mismatch: {sorted(ac6_phy ^ EXPECTED_PHY)}")
if make_core != EXPECTED_CORE:
    fail(MAKEFILE, f"core source set mismatch: {sorted(make_core ^ EXPECTED_CORE)}")
if ac6_core != EXPECTED_CORE:
    fail(CPROJECT, f"core source set mismatch: {sorted(ac6_core ^ EXPECTED_CORE)}")

# 2. Keep first-party firmware small enough to read in one pass.
phy_lines = 0
for path in sorted(PHY.glob("*.c")):
    lines = len(text(path).splitlines())
    phy_lines += lines
    if lines > 600:
        fail(path, f"{lines} lines exceeds 600-line module budget")
if phy_lines > 2000:
    fail("firmware/phy6252", f"adapter is {phy_lines} lines; budget is 2000")

production_paths = list(PHY.glob("*.c")) + list((ROOT / "firmware/src").glob("*.c")) + [TARGET]
production_lines = sum(len(text(path).splitlines()) for path in production_paths)
if production_lines > 5000:
    fail("firmware", f"first-party production C is {production_lines} lines; budget is 5000")

# 3. BLE queues are bounded backpressure, not hidden work storage.
need(TRANSPORT, "#define DPLS_RX_QUEUE_DEPTH 2u", "RX queue must stay at two slots")
need(TRANSPORT, "#define DPLS_TX_QUEUE_DEPTH 2u", "TX queue must stay at two slots")

# 4. Flash ownership: one writer, never while BLE link is active.
for path in list(PHY.glob("*.c")) + list((ROOT / "firmware/src").glob("*.c")):
    if "osal_snv_write" in text(path) and path != STORAGE:
        fail(path, "physical SNV write outside storage owner")
need(STORAGE, "if (link_active) return 0xffu;", "physical write must refuse active link")
need(STORAGE, "static uint32_t journal_timestamp[DPLS_EVENT_CAPACITY]", "compact RAM journal missing")
need(STORAGE, "static uint8_t journal_type[DPLS_EVENT_CAPACITY]", "journal type facts missing")
need(STORAGE, "static uint8_t journal_parameter[DPLS_EVENT_CAPACITY]", "journal parameter facts missing")
forbid(STORAGE, "journal_records[DPLS_EVENT_CAPACITY]", "flash record representation leaked into RAM")
need(STORAGE, "journal_dirty_mask", "journal write-behind dirty set missing")
need(STORAGE, "return link_active && (settings_dirty || auth_lock_dirty);",
     "only critical settings/auth may request controlled disconnect")

# 5. Bond ownership: no heuristic may erase a valid pairing.
for path in PHY.glob("*.c"):
    src = text(path)
    for token in (
        "DPLS_BOND_DESYNC",
        "pre_auth_disconnect",
        "bond_erase_requested",
        "dpls_ble_identity_reset_bonding_keys",
    ):
        if token in src:
            fail(path, f"forbidden bond heuristic returned: {token}")

erase_occurrences = sum(text(path).count("GAPBOND_ERASE_ALLBONDS") for path in PHY.glob("*.c"))
if erase_occurrences != 1 or "GAPBOND_ERASE_ALLBONDS" not in text(TRANSPORT):
    fail(TRANSPORT, "bond erase must exist exactly once, in transport factory-reset path")
need(TRANSPORT, "dpls_phy6252_transport_factory_forget_bonds", "explicit factory bond erase missing")
need(RUNTIME, "dpls_phy6252_transport_factory_forget_bonds()", "factory reset must own the only bond erase call")
forbid(TRANSPORT_KT, 'text.contains("encryption timed out")',
       "generic timeout must never be classified as stale bond")

# 6. Boot/identity: no RNG/SNV/HCI before controller start; identity never gates advertising.
prepare = section(IDENTITY, "void dpls_ble_identity_prepare", "void dpls_ble_identity_on_stack_started")
for token in ("LL_ENC_", "osal_snv_", "HCI_"):
    if token in prepare:
        fail(IDENTITY, f"early identity path contains {token}")
need(AUTH, "LL_ENC_GenerateTrueRandNum", "TRNG must live in post-link auth adapter")
started = section(TARGET, "case GAPROLE_STARTED:", "case GAPROLE_CONNECTED:")
if started.find("dpls_ble_identity_on_stack_started();") > started.find("enable_advertising();"):
    fail(TARGET, "stack identity must run before advertising")
if "enable_advertising();" not in started:
    fail(TARGET, "GAPROLE_STARTED must always advertise")

start_evt = section(TARGET, "if (events & SBP_START_DEVICE_EVT)", "if (events & DPLS_PHY6252_RX_EVT)")
for token in ("STORAGE", "flash", "snv"):
    if token.lower() in start_evt.lower():
        fail(TARGET, f"boot start event leaked persistence concern: {token}")

# 7. TX and settings transaction boundary: ACK leaves before flash can start.
need(GATT, "GATT_PERMIT_ENCRYPT_WRITE", "RX characteristic must require encryption")
need(GATT, "GATT_Notification", "notify compatibility path missing")
need(TARGET, "ATT_HANDLE_VALUE_CFM", "real indication confirmation missing")
need(RUNTIME, "dpls_phy6252_storage_disconnect_requested() &&", "critical persistence boundary missing")
need(RUNTIME, "dpls_phy6252_transport_tx_idle()", "critical persistence must wait for TX drain")
for token in ("osal_snv_write", "GAPBOND_ERASE_ALLBONDS", "GAPRole_TerminateConnection"):
    forbid(SERVER, token, f"domain server may not own physical side effect {token}")

# 8. Safety stays pure and mobile lifecycle stays single-owner.
need(SAFETY, "dpls_safety_required_return", "safety reducer missing")
for token in ("GAPRole_", "osal_", "GATT_"):
    forbid(SAFETY, token, f"safety depends on runtime API: {token}")
need(SESSION, "sealed interface DeviceSession", "DeviceSession lifecycle missing")
need(MACHINE, "fun reduce(state: DeviceSession, event: ConnectionEvent)", "connection reducer missing")
need(CLIENT, "private var session: DeviceSession = DeviceSession.Offline", "DplsClient must own lifecycle")
writes = [line.strip() for line in text(CLIENT).splitlines() if re.match(r"^session\s*=", line.strip())]
if writes != ["session = ConnectionMachine.reduce(session, event)"]:
    fail(CLIENT, f"unexpected lifecycle writes: {writes}")

if errors:
    print("Architecture guard failed:")
    for item in errors:
        print(f"  - {item}")
    raise SystemExit(1)

print("Architecture guard: PASS")
print(f"  first-party firmware: {production_lines} lines; PHY adapter: {phy_lines} lines")
print("  one runtime, one flash writer, one explicit bond erase path")
print("  bounded 2-slot RX/TX backpressure; no flash writes on active BLE")
