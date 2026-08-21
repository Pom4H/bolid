#!/usr/bin/env python3
"""Static contract for PHY6252 single-owner radio/flash persistence."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_H = ROOT / "firmware/phy6252/dpls_phy6252_app.h"
APP = ROOT / "firmware/phy6252/dpls_phy6252_app.c"
GUARD = ROOT / "firmware/phy6252/dpls_phy6252_snv_guard.c"
STORAGE = ROOT / "firmware/phy6252/dpls_phy6252_storage.c"
ACTOR = ROOT / "firmware/src/dpls_storage_actor.c"
IDENTITY = ROOT / "firmware/phy6252/dpls_ble_identity.c"
TARGET = ROOT / "firmware/targets/phy6252/source/dplsBLEPeripheral.c"
MAKEFILE = ROOT / "firmware/targets/phy6252/Makefile"
CPROJECT = ROOT / "firmware/targets/phy6252/test-dpls.cproject.yml"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def require(path: Path, needle: str) -> None:
    if needle not in text(path):
        raise SystemExit(f"{path.relative_to(ROOT)}: missing {needle!r}")


require(APP_H, "#define osal_snv_read dpls_phy6252_snv_read_guarded")
require(APP_H, "#define osal_snv_write dpls_phy6252_snv_write_guarded")
require(GUARD, "DPLS_STORAGE_EVT_WRITE_REQUESTED")
require(GUARD, "DPLS_STORAGE_DRAINING")
require(GUARD, "bool dpls_phy6252_snv_flush_deferred(void)")
require(GUARD, "if (dpls_phy6252_link_active()) return false;")
require(ACTOR, "dpls_storage_actor_flash_allowed")
require(ACTOR, "actor->phase == DPLS_STORAGE_FLASH")
require(ACTOR, "!actor->link_active")
require(STORAGE, "dpls_phy6252_snv_pending() || dpls_phy6252_storage_pending()")
require(STORAGE, "dpls_phy6252_flash_process_one")
require(MAKEFILE, "$(FW)/src/dpls_storage_actor.c")
require(MAKEFILE, "$(FW)/phy6252/dpls_phy6252_storage.c")
require(CPROJECT, "../../src/dpls_storage_actor.c")
require(CPROJECT, "../../phy6252/dpls_phy6252_storage.c")
require(TARGET, '#include "dpls_phy6252_storage.h"')
require(TARGET, "dpls_phy6252_flash_work_pending()")
require(TARGET, "dpls_phy6252_flash_disconnect_requested()")
require(TARGET, "dpls_phy6252_flash_process_one()")
require(TARGET, "if (!dpls_ble_identity_is_ready() || dpls_phy6252_flash_work_pending()) return false;")

# The target is intentionally storage-agnostic. Re-introducing the SNV guard or
# journal queue here would create a second owner of flash policy.
target = text(TARGET)
for forbidden in (
    "dpls_phy6252_snv_pending()",
    "dpls_phy6252_snv_flush_deferred()",
    "dpls_phy6252_storage_pending()",
    '#include "dpls_phy6252_snv_guard.h"',
):
    if forbidden in target:
        raise SystemExit(f"dplsBLEPeripheral.c: target bypasses storage actor with {forbidden!r}")

# The macro guard must be visible before the vendor SNV header in the only
# application TU that owns settings/auth/journal SNV calls.
app = text(APP)
if app.index('#include "dpls_phy6252_app.h"') > app.index('#include "osal_snv.h"'):
    raise SystemExit("dpls_phy6252_app.c: SNV guard header must precede osal_snv.h")

# The identity module owns exactly two vendor-key erases (IRK/CSRK). On normal
# commissioning boot they execute before GAPRole_StartDevice; keep this narrow
# exception explicit so no unrelated raw SNV writer can appear unnoticed.
identity = text(IDENTITY)
if identity.count("osal_snv_write(") != 2:
    raise SystemExit("dpls_ble_identity.c: expected exactly two raw IRK/CSRK writes")
require(IDENTITY, "osal_snv_write(BLE_NVID_IRK")
require(IDENTITY, "osal_snv_write(BLE_NVID_CSRK")

for path in (ROOT / "firmware/phy6252").glob("*.c"):
    if path in {APP, GUARD, IDENTITY}:
        continue
    if "osal_snv_write(" in text(path):
        raise SystemExit(f"{path.relative_to(ROOT)}: raw osal_snv_write bypasses storage actor")

print("PHY6252 storage actor contract: PASS")
