#!/usr/bin/env python3
"""PHY6252 contract: flash не конкурирует с active BLE и не блокирует boot advertising."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_H = ROOT / "firmware/phy6252/dpls_phy6252_app.h"
APP = ROOT / "firmware/phy6252/dpls_phy6252_app.c"
GUARD = ROOT / "firmware/phy6252/dpls_phy6252_snv_guard.c"
STORAGE = ROOT / "firmware/phy6252/dpls_phy6252_storage.c"
IDENTITY = ROOT / "firmware/phy6252/dpls_ble_identity.c"
TARGET = ROOT / "firmware/targets/phy6252/source/dplsBLEPeripheral.c"
MAKEFILE = ROOT / "firmware/targets/phy6252/Makefile"
CPROJECT = ROOT / "firmware/targets/phy6252/test-dpls.cproject.yml"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def require(path: Path, needle: str) -> None:
    if needle not in text(path):
        raise SystemExit(f"{path.relative_to(ROOT)}: missing {needle!r}")


# Обычные SNV операции product app проходят через guarded API.
require(APP_H, "#define osal_snv_read dpls_phy6252_snv_read_guarded")
require(APP_H, "#define osal_snv_write dpls_phy6252_snv_write_guarded")
require(GUARD, "bool dpls_phy6252_snv_flush_deferred(void)")
require(GUARD, "if (dpls_phy6252_link_active()) return false;")
require(GUARD, "return deferred.pending && dpls_phy6252_link_active();")
require(GUARD, "if (deferred.pending && deferred.id != id)")

# Один facade выводит flash work из реальных очередей.
require(STORAGE, "dpls_phy6252_snv_pending() || dpls_phy6252_storage_pending()")
require(STORAGE, "bool dpls_phy6252_flash_process_one(void)")
require(STORAGE, "if (dpls_phy6252_link_active()) return false;")
require(MAKEFILE, "$(FW)/phy6252/dpls_phy6252_storage.c")
require(CPROJECT, "../../phy6252/dpls_phy6252_storage.c")

for legacy in (
    ROOT / "firmware/include/dpls_storage_actor.h",
    ROOT / "firmware/src/dpls_storage_actor.c",
    ROOT / "firmware/tests/test_storage_actor.c",
):
    if legacy.exists():
        raise SystemExit(f"{legacy.relative_to(ROOT)}: storage actor must stay removed")

# Target знает только facade; boot advertising не зависит от pending flash.
require(TARGET, '#include "dpls_phy6252_storage.h"')
require(TARGET, "dpls_phy6252_flash_work_pending()")
require(TARGET, "dpls_phy6252_flash_disconnect_requested()")
require(TARGET, "dpls_phy6252_flash_process_one()")
require(TARGET, "dpls_phy6252_flash_disconnect_requested() &&")
require(TARGET, "dpls_phy6252_tx_idle()")
require(TARGET, "case GAPROLE_STARTED:")
require(TARGET, "enable_advertising();")
require(TARGET, "~DPLS_PHY6252_STORAGE_EVT")

target = text(TARGET)
started = target[target.index("case GAPROLE_STARTED:"):target.index("case GAPROLE_CONNECTED:")]
if "dpls_phy6252_flash_work_pending()" in started:
    raise SystemExit("dplsBLEPeripheral.c: boot advertising must not wait for flash")
if "dpls_ble_identity_is_ready" in target:
    raise SystemExit("dplsBLEPeripheral.c: identity-ready advertising gate must not return")
for forbidden in (
    "dpls_phy6252_snv_pending()",
    "dpls_phy6252_snv_flush_deferred()",
    "dpls_phy6252_storage_pending()",
    '#include "dpls_phy6252_snv_guard.h"',
):
    if forbidden in target:
        raise SystemExit(f"dplsBLEPeripheral.c: target bypasses storage facade with {forbidden!r}")

app = text(APP)
if app.index('#include "dpls_phy6252_app.h"') > app.index('#include "osal_snv.h"'):
    raise SystemExit("dpls_phy6252_app.c: SNV guard header must precede osal_snv.h")

# BLE identity — отдельный pre-link boot boundary, как в рабочем 1.4.0.
# Здесь разрешены SNV MAC/IRK/CSRK read/write до GAPRole_StartDevice и явный
# reset bonding keys. Raw arbitrary flash sector и factory sidecar запрещены.
identity = text(IDENTITY)
for required in (
    "check_chip_mAddr();",
    "DPLS_BLE_MAC_SNV_ID 0x82u",
    "read_mac_snv",
    "write_mac_snv",
    "read_key_snv",
    "write_key_snv",
    "HCI_EXT_SetBDADDRCmd",
):
    require(IDENTITY, required)
for forbidden in (
    "hal_flash_read",
    "DPLS_FACTORY_IDENTITY_FLASH_ADDR",
    "0x1103F000",
    "dpls_ble_identity_is_ready",
):
    if forbidden in identity:
        raise SystemExit(f"dpls_ble_identity.c: boot-breaking factory path returned: {forbidden}")

# Никакой другой PHY6252 unit не пишет raw SNV в обход app guard / identity boot boundary.
for path in (ROOT / "firmware/phy6252").glob("*.c"):
    if path in {APP, GUARD, IDENTITY}:
        continue
    if "osal_snv_write(" in text(path):
        raise SystemExit(f"{path.relative_to(ROOT)}: raw osal_snv_write bypasses storage facade")

print("PHY6252 storage/boot flash contract: PASS")
