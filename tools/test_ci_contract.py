#!/usr/bin/env python3
"""Static contract for the release CI topology.

This does not replace GitHub branch protection. It prevents accidental edits
that silently turn an RC pull request back into the cheap PR-only matrix.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CI = ROOT / ".github/workflows/ci.yml"
CMAKE = ROOT / "firmware/CMakeLists.txt"
HOST_GATE = ROOT / "tools/run_host_invariant_gate.sh"

ci = CI.read_text(encoding="utf-8")
cmake = CMAKE.read_text(encoding="utf-8")
host_gate = HOST_GATE.read_text(encoding="utf-8")

required_ci_tokens = [
    "release_pr:",
    "head_ref",
    "Host invariants + sanitizers",
    "Android unit tests",
    "PHY6252 Firmverse",
    "Firmware coverage + cppcheck",
    "Soft-BLE DplsClient ↔ simulator",
    "PHY6252 GNU Arm GCC",
    "PHY6252 Keil MDK / AC6",
    "iOS adapter + Xcode host",
    "RC production gate",
    "needs.smoke.outputs.release_pr == 'true'",
]

missing = [token for token in required_ci_tokens if token not in ci]
if missing:
    raise SystemExit("CI release contract missing: " + ", ".join(missing))

# These jobs used to be completely disabled for pull requests. A release PR
# must never regress to that topology.
forbidden = [
    "firmware-quality:\n    name: Firmware coverage + cppcheck\n    if: github.event_name != 'pull_request'",
    "soft-ble-e2e:\n    name: Soft-BLE DplsClient ↔ simulator\n    if: github.event_name != 'pull_request'",
    "firmware-gcc:\n    name: PHY6252 GNU Arm GCC\n    if: github.event_name != 'pull_request'",
    "firmware-keil:\n    name: PHY6252 Keil MDK / AC6\n    if: github.event_name != 'pull_request'",
    "ios:\n    name: iOS adapter + Xcode host\n    if: github.event_name != 'pull_request'",
]
for pattern in forbidden:
    if pattern in ci:
        raise SystemExit("CI release contract regressed: heavy job disabled on every PR")

for token in ("ENABLE_SANITIZERS", "-fsanitize=address,undefined", "test_protocol_fuzz"):
    if token not in cmake:
        raise SystemExit(f"host sanitizer/fuzz contract missing from firmware/CMakeLists.txt: {token}")

for token in ("ENABLE_SANITIZERS=ON", "ctest --test-dir", "architecture_guard.py"):
    if token not in host_gate:
        raise SystemExit(f"host invariant gate missing: {token}")

print("CI production contract: OK")
