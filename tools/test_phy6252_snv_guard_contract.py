#!/usr/bin/env python3
"""Статический contract для PHY6252: flash никогда не конкурирует с активным BLE link."""
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


# Все обычные SNV операции из PHY app проходят через guarded API.
require(APP_H, "#define osal_snv_read dpls_phy6252_snv_read_guarded")
require(APP_H, "#define osal_snv_write dpls_phy6252_snv_write_guarded")
require(GUARD, "bool dpls_phy6252_snv_flush_deferred(void)")
require(GUARD, "if (dpls_phy6252_link_active()) return false;")
require(GUARD, "return deferred.pending && dpls_phy6252_link_active();")
require(GUARD, "if (deferred.pending && deferred.id != id)")

# Единый facade выводит наличие flash-работы из реальных очередей, а не из
# дублирующего state machine.
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
        raise SystemExit(f"{legacy.relative_to(ROOT)}: legacy storage actor must be removed")

for build_file in (MAKEFILE, CPROJECT):
    if "dpls_storage_actor" in text(build_file):
        raise SystemExit(f"{build_file.relative_to(ROOT)}: legacy storage actor is still compiled")

# Target видит только facade и не лезет напрямую в SNV/journal ownership.
require(TARGET, '#include "dpls_phy6252_storage.h"')
require(TARGET, "dpls_phy6252_flash_work_pending()")
require(TARGET, "dpls_phy6252_flash_disconnect_requested()")
require(TARGET, "dpls_phy6252_flash_process_one()")
require(TARGET, "if (!dpls_ble_identity_is_ready() || dpls_phy6252_flash_work_pending()) return false;")
require(TARGET, "dpls_phy6252_flash_disconnect_requested() &&")
require(TARGET, "dpls_phy6252_tx_idle()")

target = text(TARGET)
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

# Factory identity не содержит runtime migration/personalization. Два прямых
# SNV write здесь разрешены только для явного сброса IRK/CSRK bond-копий.
identity = text(IDENTITY)
if identity.count("osal_snv_write(") != 2:
    raise SystemExit("dpls_ble_identity.c: expected exactly two raw IRK/CSRK reset writes")
require(IDENTITY, "osal_snv_write(BLE_NVID_IRK")
require(IDENTITY, "osal_snv_write(BLE_NVID_CSRK")
for forbidden in (
    "DPLS_LEGACY_BLE_MAC",
    "read_legacy_mac_snv",
    "legacy_device_id_from_mac",
    "LL_ENC_GenerateTrueRandNum",
    "write_key_snv",
):
    if forbidden in identity:
        raise SystemExit(f"dpls_ble_identity.c: build-time identity logic leaked into runtime: {forbidden}")

for path in (ROOT / "firmware/phy6252").glob("*.c"):
    if path in {APP, GUARD, IDENTITY}:
        continue
    if "osal_snv_write(" in text(path):
        raise SystemExit(f"{path.relative_to(ROOT)}: raw osal_snv_write bypasses storage facade")

print("PHY6252 storage facade contract: PASS")
