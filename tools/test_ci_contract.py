#!/usr/bin/env python3
"""Release CI/DX contract for the single PHY6252 production toolchain."""
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIRMVERSE_SHA = "b1a92e3e6f941bf0f55049087d6cb10dd76f1045"
ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
dx = (ROOT / ".github/workflows/firmware-dx.yml").read_text(encoding="utf-8")
harness = (ROOT / ".github/workflows/firmverse-flash-harness.yml").read_text(encoding="utf-8")
host = (ROOT / "tools/run_host_invariant_gate.sh").read_text(encoding="utf-8")
build = (ROOT / "tools/build_firmware.sh").read_text(encoding="utf-8")
flash = (ROOT / "tools/flash_firmware.sh").read_text(encoding="utf-8")
cproject = (ROOT / "firmware/targets/phy6252/test-dpls.cproject.yml").read_text(encoding="utf-8")
solution = (ROOT / "firmware/targets/phy6252/test-dpls.csolution.yml").read_text(encoding="utf-8")
vcpkg = json.loads((ROOT / "firmware/targets/phy6252/vcpkg-configuration.json").read_text(encoding="utf-8"))

for script in (ROOT / "tools/build_firmware.sh", ROOT / "tools/flash_firmware.sh"):
    subprocess.run(["bash", "-n", str(script)], check=True)

if not re.fullmatch(r"[0-9a-f]{40}", FIRMVERSE_SHA):
    raise SystemExit("Firmverse must be pinned by a full immutable commit SHA")
if ci.count(f"Pom4H/firmverse@{FIRMVERSE_SHA}") != 1:
    raise SystemExit("CI must reference the pinned Firmverse revision exactly once")
if harness.count(f"ref: {FIRMVERSE_SHA}") != 1:
    raise SystemExit("flash harness must reference the same Firmverse revision exactly once")

for token in (
    "workflow_dispatch:",
    "full_matrix:",
    "EVENT_ACTION:",
    "BEFORE_SHA:",
    "Android unit tests",
    "PHY6252 firmware / Arm Compiler 6.24",
    "PHY6252 Firmverse",
    f"Pom4H/firmverse@{FIRMVERSE_SHA}",
    "Firmware coverage + cppcheck",
    "Soft-BLE DplsClient ↔ simulator",
    "iOS adapter + Xcode host",
    "RC production gate",
    "RC affected-area production gate: PASS",
    "BLE HCI LE_SetAdvEnable enabled=1",
    "actions/download-artifact@v7",
    "test-dpls-phy6252-1.4.2-rc9",
):
    if token not in ci:
        raise SystemExit(f"CI contract missing: {token}")

for token in (
    '"$EVENT_ACTION" == "synchronize"',
    'base="$BEFORE_SHA"',
    'git diff --name-only "$base" "$head"',
):
    if token not in ci:
        raise SystemExit(f"incremental PR diff contract missing: {token}")

if "cancel-in-progress: true" in ci:
    raise SystemExit("affected-area CI must not cancel an earlier proof")
if "1.4.2-rc8" in ci or "rc=8" in ci:
    raise SystemExit("RC9 CI still publishes RC8-labelled artifacts")

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

for workflow_name, workflow in (("ci.yml", ci), ("firmware-dx.yml", dx), ("firmverse-flash-harness.yml", harness)):
    for token in (
        "ubuntu-22.04",
        "ARM-software/cmsis-actions/vcpkg@v1",
        "firmware/targets/phy6252/vcpkg-configuration.json",
        "ARM-software/cmsis-actions/armlm@v1",
        "tools/build_firmware.sh",
    ):
        if token not in workflow:
            raise SystemExit(f"{workflow_name}: production toolchain bootstrap missing: {token}")

if "[keil|ac6|" in build or "TOOLCHAIN=" in build:
    raise SystemExit("build_firmware.sh regained a toolchain selector")
if "Arm Compiler 6.24.0" not in build:
    raise SystemExit("build_firmware.sh lost the production compiler contract")

for token in ('DEBUG_INFO: "0"', 'DPLS_CONNECTED_SLEEP: "1"'):
    if token not in cproject:
        raise SystemExit(f"low-power production default missing: {token}")
if 'DEBUG_INFO: "1"' in cproject or "-DDEBUG_INFO=1" in cproject:
    raise SystemExit("production build regained unconditional UART logging")

requires = vcpkg.get("requires", {})
expected = {
    "arm:tools/kitware/cmake": "3.31.12",
    "arm:tools/ninja-build/ninja": "1.13.2",
    "arm:compilers/arm/armclang": "6.24.0",
    "arm:tools/open-cmsis-pack/cmsis-toolbox": "2.14.1",
}
for package, version in expected.items():
    actual = requires.get(package)
    if actual != version:
        raise SystemExit(f"toolchain pin mismatch for {package}: expected {version}, got {actual!r}")
if "created-for: CMSIS-Toolbox@2.14.1" not in solution or "compiler: AC6" not in solution:
    raise SystemExit("solution contract must be CMSIS-Toolbox 2.14.1 + AC6")

if "/Users/" in build or "/Users/" in flash:
    raise SystemExit("local developer path leaked into firmware scripts")
if (ROOT / "tools/flash_firmware_agent.sh").exists():
    raise SystemExit("separate agent flasher returned")
for forbidden in ("--auto-rst", "setRTS", "setDTR", "controlled_connect"):
    if forbidden in flash:
        raise SystemExit(f"production flasher regained unsupported auto-reset path: {forbidden}")
if '-r wh "$HEX"' not in flash or "зажмите KEY1" not in flash:
    raise SystemExit("production flasher lost manual KEY1 + vendor wh contract")

# The old second target compiler is forbidden in first-party filenames and text.
legacy = "".join(("g", "c", "c"))
text_suffixes = {".yml", ".yaml", ".sh", ".py", ".md", ".c", ".h", ".kt", ".kts", ".swift", ".pbxproj", ".json", ".toml", ".txt", ".env", ".sct"}
for path in ROOT.rglob("*"):
    if not path.is_file() or "third_party" in path.parts or ".git" in path.parts:
        continue
    if legacy in path.name.casefold():
        raise SystemExit(f"legacy target toolchain filename remains: {path.relative_to(ROOT)}")
    if path.suffix.lower() not in text_suffixes and path.name not in {"Makefile"}:
        continue
    try:
        data = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    if legacy in data.casefold():
        raise SystemExit(f"legacy target toolchain reference remains: {path.relative_to(ROOT)}")

print("CI/DX contract: PASS")
print("  one production PHY6252 image and one compiler path")
print("  Firmverse consumes the exact production artifact")
print("  Firmverse action and flash harness share one immutable revision")
print("  Arm Compiler 6.24.0 + CMSIS-Toolbox 2.14.1 are pinned")
print("  PB-03F flashing: manual KEY1 + vendor wh")
