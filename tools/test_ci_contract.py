#!/usr/bin/env python3
"""Release evidence and firmware toolchain contract.

AC6 bootstrap has one owner: GitHub Actions. build_firmware.sh only consumes an
already activated toolchain; it must not grow a second installer/license path.
"""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
dx = (ROOT / ".github/workflows/firmware-dx.yml").read_text(encoding="utf-8")
host = (ROOT / "tools/run_host_invariant_gate.sh").read_text(encoding="utf-8")
build = (ROOT / "tools/build_firmware.sh").read_text(encoding="utf-8")
flash = (ROOT / "tools/flash_firmware.sh").read_text(encoding="utf-8")
vcpkg = (ROOT / "firmware/targets/phy6252/vcpkg-configuration.json").read_text(encoding="utf-8")
solution = (ROOT / "firmware/targets/phy6252/test-dpls.csolution.yml").read_text(encoding="utf-8")

for script in (ROOT / "tools/build_firmware.sh", ROOT / "tools/flash_firmware.sh"):
    subprocess.run(["bash", "-n", str(script)], check=True)

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

# Production CI and fresh-clone DX deliberately share one AC6 bootstrap path.
for workflow_name, workflow in (("ci.yml", ci), ("firmware-dx.yml", dx)):
    for token in (
        "ubuntu-22.04",
        "ARM-software/cmsis-actions/vcpkg@v1",
        "firmware/targets/phy6252/vcpkg-configuration.json",
        "ARM-software/cmsis-actions/armlm@v1",
        "tools/build_firmware.sh keil",
    ):
        if token not in workflow:
            raise SystemExit(f"{workflow_name}: AC6 bootstrap contract missing: {token}")

# The build script is a build script, not another package manager/license client.
for forbidden in (
    "vcpkg activate",
    'VCPKG_VERSION=',
    "KEMDK-COM0",
    "armlm activate",
):
    if forbidden in build:
        raise SystemExit(f"second AC6 bootstrap path leaked into build_firmware.sh: {forbidden}")

for token in ('"6.24.0"', '"2.14.1"'):
    if token not in vcpkg:
        raise SystemExit(f"pinned toolchain contract missing: {token}")

for token in ("CMSIS-Toolbox@2.14.1", "AC6@6.24.0"):
    if token not in solution:
        raise SystemExit(f"solution toolchain contract missing: {token}")

if "/Users/" in build or "/Users/" in flash:
    raise SystemExit("local developer path leaked into firmware scripts")
if (ROOT / "tools/flash_firmware_agent.sh").exists():
    raise SystemExit("separate agent flasher returned")
if "--auto-rst" not in flash:
    raise SystemExit("single flasher lost --auto-rst")

print("CI/DX contract: PASS")
print("  one AC6 6.24 + Community license bootstrap: GitHub Actions")
