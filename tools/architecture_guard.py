#!/usr/bin/env python3
"""RC9 ownership invariants for the single production source graph."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PHY = ROOT / "firmware/phy6252"
TARGET = ROOT / "firmware/targets/phy6252/source/dplsBLEPeripheral.c"
CPROJECT = ROOT / "firmware/targets/phy6252/test-dpls.cproject.yml"
SERVER = ROOT / "firmware/src/dpls_server.c"
SAFETY = ROOT / "firmware/src/dpls_safety.c"
SAFETY_H = ROOT / "firmware/include/dpls_safety.h"
GATT = PHY / "dpls_gatt_service.c"
IDENTITY = PHY / "dpls_ble_identity.c"
RUNTIME = PHY / "dpls_phy6252_runtime.c"
STORAGE = PHY / "dpls_phy6252_storage.c"
TRANSPORT = PHY / "dpls_phy6252_transport.c"
OUTPUTS = PHY / "dpls_phy6252_outputs.c"
POWER = PHY / "dpls_phy6252_power.c"
AUTH = PHY / "dpls_phy6252_auth.c"
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
MACHINE = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/ConnectionMachine.kt"
SESSION = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/DeviceSession.kt"
TRANSPORT_KT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsTransport.kt"

EXPECTED_PHY = {
    "dpls_gatt_service.c", "dpls_ble_identity.c", "dpls_phy6252_auth.c",
    "dpls_phy6252_measurements.c", "dpls_phy6252_outputs.c",
    "dpls_phy6252_power.c", "dpls_phy6252_runtime.c", "dpls_phy6252_storage.c",
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

# The CMSIS project is the only production source manifest.
for name in REMOVED:
    if (PHY / name).exists():
        fail(PHY / name, "removed runtime/duplicate returned")
cproject = text(CPROJECT)
ac6_phy = set(re.findall(r"\.\./\.\./phy6252/(dpls[^\s]+\.c)", cproject))
ac6_core = set(re.findall(r"\.\./\.\./src/(dpls[^\s]+\.c)", cproject))
if ac6_phy != EXPECTED_PHY:
    fail(CPROJECT, f"PHY source set mismatch: {sorted(ac6_phy ^ EXPECTED_PHY)}")
if ac6_core != EXPECTED_CORE:
    fail(CPROJECT, f"core source set mismatch: {sorted(ac6_core ^ EXPECTED_CORE)}")

# Cognitive and static-memory budgets.
phy_lines = 0
for path in sorted(PHY.glob("*.c")):
    lines = len(text(path).splitlines())
    phy_lines += lines
    if lines > 600:
        fail(path, f"{lines} lines exceeds 600-line module budget")
if phy_lines > 2300:
    fail("firmware/phy6252", f"adapter is {phy_lines} lines; budget 2300")
production_paths = list(PHY.glob("*.c")) + list((ROOT / "firmware/src").glob("*.c")) + [TARGET]
production_lines = sum(len(text(path).splitlines()) for path in production_paths)
if production_lines > 5000:
    fail("firmware", f"production C is {production_lines} lines; budget 5000")
for path in production_paths:
    src = text(path)
    for token in ("malloc(", "calloc(", "realloc(", "free("):
        if token in src:
            fail(path, f"runtime heap forbidden: {token}")

# One physical BLE link owner.
need(TRANSPORT, "static uint16 connection_handle", "transport must own the physical link fact")
forbid(STORAGE, "link_active", "storage must not shadow BLE link state")
forbid(TARGET, "link_up", "target must not shadow BLE link state")
need(TARGET, "dpls_phy6252_runtime_link_active()", "target must query runtime link fact")

# One logical mode owner; output layer is a stateless actuator.
forbid(OUTPUTS, "hardware_mode", "outputs must not copy logical mode")
forbid(OUTPUTS, "dpls_phy6252_outputs_mode", "second mode getter returned")
need(RUNTIME, "server.safety.mode", "runtime must consume canonical safety mode")

# Bounded request/response transport.
need(TRANSPORT, "#define DPLS_RX_QUEUE_DEPTH 1u", "RX must serialize transactions")
need(TRANSPORT, "#define DPLS_TX_QUEUE_DEPTH 2u", "TX must allow in-flight + one queued response")
need(TRANSPORT, "(uint8)(rx.count + tx.count) >= DPLS_TX_QUEUE_DEPTH", "RX must reserve response capacity")
need(RUNTIME, "dpls_gatt_add_service(receive_frame)", "GATT must enter through runtime admission")

# One physical SNV writer; writes require an offline radio fact.
for path in list(PHY.glob("*.c")) + list((ROOT / "firmware/src").glob("*.c")):
    if "osal_snv_write" in text(path) and path != STORAGE:
        fail(path, "physical SNV write outside storage owner")
need(STORAGE, "dpls_phy6252_storage_process_one(bool radio_offline)", "offline write boundary missing")
need(STORAGE, "if (!radio_offline) return false;", "storage must refuse online writes")
need(RUNTIME, "dpls_phy6252_transport_tx_idle()", "persistence must wait for TX drain")

# One power-manager owner; dangerous outputs acquire the constraint before GPIO.
for path in (TARGET, OUTPUTS, RUNTIME, STORAGE, TRANSPORT, AUTH, PHY / "dpls_phy6252_measurements.c"):
    for token in ("hal_pwrmgr_lock(", "hal_pwrmgr_unlock(", "hal_pwrmgr_register("):
        forbid(path, token, f"power-manager bypass: {token}")
need(POWER, "dpls_phy6252_power_acquire", "central power acquire missing")
need(POWER, "dpls_phy6252_power_release", "central power release missing")
lock_token = "if (!dpls_phy6252_power_acquire(DPLS_POWER_OUTPUT)) return false;"
need(OUTPUTS, lock_token, "dangerous mode must acquire output constraint")
if text(OUTPUTS).find(lock_token) > text(OUTPUTS).find("hal_gpio_write(DPLS_PIN_ISO_T, 1)"):
    fail(OUTPUTS, "dangerous GPIO may energize before power constraint")

# Bond erase exists only behind physical factory reset.
for path in PHY.glob("*.c"):
    for token in ("DPLS_BOND_DESYNC", "pre_auth_disconnect", "bond_erase_requested", "dpls_ble_identity_reset_bonding_keys"):
        if token in text(path):
            fail(path, f"forbidden bond heuristic returned: {token}")
erases = sum(text(path).count("GAPBOND_ERASE_ALLBONDS") for path in PHY.glob("*.c"))
if erases != 1 or "GAPBOND_ERASE_ALLBONDS" not in text(TRANSPORT):
    fail(TRANSPORT, "bond erase must exist exactly once in factory-reset path")
need(RUNTIME, "dpls_phy6252_transport_factory_forget_bonds()", "factory reset must own bond erase call")
forbid(TRANSPORT_KT, 'text.contains("encryption timed out")', "generic timeout cannot mean stale bond")

# Boot identity cannot mutate controller lifecycle too early.
prepare = section(IDENTITY, "void dpls_ble_identity_prepare", "void dpls_ble_identity_on_stack_started")
for token in ("LL_ENC_", "osal_snv_", "HCI_"):
    if token in prepare:
        fail(IDENTITY, f"early identity path contains {token}")
need(AUTH, "LL_ENC_GenerateTrueRandNum", "TRNG must live in post-link auth adapter")
started = section(TARGET, "case GAPROLE_STARTED:", "case GAPROLE_CONNECTED:")
if "enable_advertising();" not in started:
    fail(TARGET, "started state must advertise")
if started.find("dpls_ble_identity_on_stack_started();") > started.find("enable_advertising();"):
    fail(TARGET, "controller identity must be set before advertising")

# Pure fail-safe policy.
need(SAFETY_H, "bool measurements_ready;", "measurement validity must be a safety input")
need(SAFETY, "dpls_safety_admission_reason", "dangerous-mode admission policy missing")
need(SAFETY, "DPLS_SAFETY_RETURN_MEASUREMENT_LOST", "measurement-loss fail-safe missing")
for token in ("GAPRole_", "osal_", "GATT_"):
    forbid(SAFETY, token, f"pure safety depends on runtime API: {token}")
for token in ("osal_snv_write", "GAPBOND_ERASE_ALLBONDS", "GAPRole_TerminateConnection"):
    forbid(SERVER, token, f"domain server owns physical side effect {token}")

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
    for item in errors:
        print(f"  - {item}")
    raise SystemExit(1)
print("Architecture guard: PASS")
print(f"  first-party firmware: {production_lines} lines; PHY adapter: {phy_lines} lines")
print("  one production source graph, one link owner, one mode owner, one flash writer, one power owner")
