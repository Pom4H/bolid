#!/usr/bin/env python3
"""Release evidence, affected-area CI, power-build and toolchain contract.

AC6 bootstrap has one owner: GitHub Actions. Production firmware has logging off
and connected sleep on. A separate deterministic A/B helper builds the historical
link guard without source edits for PB-03F measurements.
"""
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIRMVERSE_SHA = "0c2e3b915db507448eb158cd0a49ccd1c2362b59"
ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
dx = (ROOT / ".github/workflows/firmware-dx.yml").read_text(encoding="utf-8")
host = (ROOT / "tools/run_host_invariant_gate.sh").read_text(encoding="utf-8")
build = (ROOT / "tools/build_firmware.sh").read_text(encoding="utf-8")
power_ab_path = ROOT / "tools/build_power_ab.sh"
power_ab = power_ab_path.read_text(encoding="utf-8")
flash = (ROOT / "tools/flash_firmware.sh").read_text(encoding="utf-8")
makefile = (ROOT / "firmware/targets/phy6252/Makefile").read_text(encoding="utf-8")
cproject = (ROOT / "firmware/targets/phy6252/test-dpls.cproject.yml").read_text(encoding="utf-8")
vcpkg = json.loads((ROOT / "firmware/targets/phy6252/vcpkg-configuration.json").read_text(encoding="utf-8"))
solution = (ROOT / "firmware/targets/phy6252/test-dpls.csolution.yml").read_text(encoding="utf-8")

for script in (
    ROOT / "tools/build_firmware.sh",
    power_ab_path,
    ROOT / "tools/flash_firmware.sh",
):
    subprocess.run(["bash", "-n", str(script)], check=True)

for token in (
    "workflow_dispatch:",
    "full_matrix:",
    "EVENT_ACTION:",
    "BEFORE_SHA:",
    "shared_mobile=false",
    "android_only=false",
    "ios_only=false",
    "if: needs.smoke.outputs.android == 'true'",
    "if: needs.smoke.outputs.ios == 'true'",
    "Android unit tests",
    "PHY6252 Firmverse",
    f"Pom4H/firmverse@{FIRMVERSE_SHA}",
    "Firmware coverage + cppcheck",
    "Soft-BLE DplsClient ↔ simulator",
    "PHY6252 GNU Arm GCC",
    "PHY6252 Keil MDK / AC6",
    "iOS adapter + Xcode host",
    "RC production gate",
    "RC affected-area production gate: PASS",
    "BLE HCI LE_SetAdvEnable enabled=1",
    "max-insns: '3000000'",
):
    if token not in ci:
        raise SystemExit(f"CI contract missing: {token}")

if "Pom4H/firmverse@v1" in ci or "Pom4H/firmverse@main" in ci:
    raise SystemExit("Firmverse must be pinned to the validated pulled revision, not a mutable ref")

# A synchronize event uses the previous PR head, otherwise an old mobile change
# rebuilds Android/iOS on every subsequent firmware-only push.
for token in (
    '"$EVENT_ACTION" == "synchronize"',
    'base="$BEFORE_SHA"',
    'git diff --name-only "$base" "$head"',
):
    if token not in ci:
        raise SystemExit(f"incremental PR diff contract missing: {token}")

# Runs are intentionally independent. There is no concurrency group, so the
# only forbidden form is one that explicitly cancels an earlier proof.
if "cancel-in-progress: true" in ci:
    raise SystemExit("affected-area CI must not cancel the only proof for an earlier change set")

# Release branches no longer force unrelated platforms. The release gate knows
# whether a skipped job was intentionally unaffected.
for forbidden in (
    'release_pr=true\n            mobile=true',
    "needs.smoke.outputs.release_pr == 'true' || needs.smoke.outputs.mobile == 'true'",
    "if: github.event_name != 'pull_request' || needs.smoke.outputs.release_pr == 'true'",
):
    if forbidden in ci:
        raise SystemExit(f"CI regained cumulative release-matrix forcing: {forbidden}")

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

for removed in (
    "test_factory_identity.py",
    "test_phy6252_linker_parity.py",
    "test_phy6252_snv_guard_contract.py",
    "test_android_ble_connection_contract.py",
):
    if removed in host:
        raise SystemExit(f"removed duplicate contract returned: {removed}")

# Production/current-measurement binaries must not contain the application's
# UART LOG traffic. Connected sleep is the RC9 candidate; the historical guard
# is available only through an explicit build override for A/B comparison.
for token in (
    "DEBUG_INFO ?= 0",
    "DPLS_CONNECTED_SLEEP ?= 1",
    "-DDEBUG_INFO=$(DEBUG_INFO)",
    "-DDPLS_CONNECTED_SLEEP=$(DPLS_CONNECTED_SLEEP)",
):
    if token not in makefile:
        raise SystemExit(f"GCC low-power production default missing: {token}")
for token in ('DEBUG_INFO: "0"', 'DPLS_CONNECTED_SLEEP: "1"'):
    if token not in cproject:
        raise SystemExit(f"AC6 low-power production default missing: {token}")
for forbidden in ('DEBUG_INFO: "1"', "-DDEBUG_INFO=1"):
    if forbidden in cproject or forbidden in makefile:
        raise SystemExit(f"production build regained unconditional UART logging: {forbidden}")

for token in (
    "DPLS_CONNECTED_SLEEP=1",
    "DPLS_CONNECTED_SLEEP=0",
    "DEBUG_INFO=0",
    "TestDPLS-1.4.2-rc9-low-power.hex",
    "TestDPLS-1.4.2-rc9-link-guard.hex",
):
    if token not in power_ab:
        raise SystemExit(f"power A/B build contract missing: {token}")

# Production CI and fresh-clone DX share one AC6 bootstrap path.
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

for forbidden in (
    "vcpkg activate",
    'VCPKG_VERSION=',
    "KEMDK-COM0",
    "armlm activate",
):
    if forbidden in build:
        raise SystemExit(f"second AC6 bootstrap path leaked into build_firmware.sh: {forbidden}")

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
    raise SystemExit("solution toolchain contract must be CMSIS-Toolbox 2.14.1 + AC6")

if "/Users/" in build or "/Users/" in flash:
    raise SystemExit("local developer path leaked into firmware scripts")
if (ROOT / "tools/flash_firmware_agent.sh").exists():
    raise SystemExit("separate agent flasher returned")

# PB-03F kit exposes only TX/RX on its normal USB-UART path. Keep the release
# contract on manual KEY1 + vendor wh; unsupported RTS/DTR auto reset stays out.
for forbidden in ("--auto-rst", "setRTS", "setDTR", "controlled_connect"):
    if forbidden in flash:
        raise SystemExit(f"production flasher regained unsupported auto-reset path: {forbidden}")
if '-r wh "$HEX"' not in flash or "зажмите KEY1" not in flash:
    raise SystemExit("production flasher lost manual KEY1 + vendor wh contract")

print("CI/DX contract: PASS")
print("  affected-area CI: previous PR head -> new head; no proof cancellation")
print("  Android/iOS run only for shared or platform-specific mobile changes")
print("  production: DEBUG_INFO=0, connected sleep enabled")
print("  reproducible GCC A/B pair isolates connected-sleep delta")
print("  manual full_matrix remains available for final release evidence")
print("  exact AC6 6.24.0 + CMSIS-Toolbox 2.14.1 bootstrap: GitHub Actions")
print("  PB-03F flashing: manual KEY1 + vendor wh, no unsupported auto-reset")
print(f"  Firmverse pinned at {FIRMVERSE_SHA[:8]} (ROM-UART flash/reset/boot proof)")
