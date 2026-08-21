#!/usr/bin/env python3
"""Small guard for the release evidence matrix."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
host = (ROOT / "tools/run_host_invariant_gate.sh").read_text(encoding="utf-8")

for token in (
    'if [[ "$HEAD_REF" == release/* ]]',
    "Android unit tests",
    "PHY6252 Firmverse",
    "Firmware coverage + cppcheck",
    "Soft-BLE DplsClient ↔ simulator",
    "PHY6252 GNU Arm GCC",
    "PHY6252 Keil MDK / AC6",
    "iOS adapter + Xcode host",
    "RC production gate",
    "BLE HCI LE_SetAdvEnable enabled=1",
):
    if token not in ci:
        raise SystemExit(f"CI contract missing: {token}")

for token in (
    "test_flash_firmware.py",
    "test_ble_timeout_contract.py",
    "test_dpls_protocol_crc.py",
    "architecture_guard.py",
    "ENABLE_SANITIZERS=ON",
    "test_differential_replay.py",
):
    if token not in host:
        raise SystemExit(f"host gate missing: {token}")

for removed in (
    "test_factory_identity.py",
    "test_phy6252_linker_parity.py",
    "test_phy6252_snv_guard_contract.py",
    "test_android_ble_connection_contract.py",
):
    if removed in host:
        raise SystemExit(f"removed duplicate contract returned: {removed}")

print("CI contract: PASS")
