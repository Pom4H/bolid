#!/usr/bin/env python3
"""Static contract for PHY6252 radio-safe application SNV ownership."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_H = ROOT / "firmware/phy6252/dpls_phy6252_app.h"
APP = ROOT / "firmware/phy6252/dpls_phy6252_app.c"
GUARD = ROOT / "firmware/phy6252/dpls_phy6252_snv_guard.c"
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
require(GUARD, "if (!dpls_phy6252_link_active())")
require(GUARD, "disconnect_requested = true;")
require(GUARD, "bool dpls_phy6252_snv_flush_deferred(void)")
require(GUARD, "if (dpls_phy6252_link_active()) return false;")
require(MAKEFILE, "$(FW)/phy6252/dpls_phy6252_snv_guard.c")
require(CPROJECT, "../../phy6252/dpls_phy6252_snv_guard.c")
require(TARGET, '#include "dpls_phy6252_snv_guard.h"')
require(TARGET, "return dpls_phy6252_snv_pending() || dpls_phy6252_storage_pending();")
require(TARGET, "dpls_phy6252_snv_pending() && !dpls_phy6252_snv_flush_deferred()")
require(TARGET, "dpls_phy6252_snv_disconnect_requested()")
require(TARGET, "if (!dpls_ble_identity_is_ready() || flash_work_pending()) return false;")

# The macro guard must be visible before the vendor SNV header in the only
# application TU that owns settings/auth/journal SNV calls.
app = text(APP)
if app.index('#include "dpls_phy6252_app.h"') > app.index('#include "osal_snv.h"'):
    raise SystemExit("dpls_phy6252_app.c: SNV guard header must precede osal_snv.h")

# No second project-owned PHY6252 TU may call raw osal_snv_write. The guard is
# the sole physical writer; the app spelling is macro-routed by app.h.
for path in (ROOT / "firmware/phy6252").glob("*.c"):
    if path in {APP, GUARD}:
        continue
    if "osal_snv_write(" in text(path):
        raise SystemExit(f"{path.relative_to(ROOT)}: raw osal_snv_write bypasses guard")

print("PHY6252 deferred SNV contract: PASS")
