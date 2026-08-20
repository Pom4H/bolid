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
    "HEAD_REF: ${{ github.head_ref }}",
    'if [[ "$HEAD_REF" == release/* ]]',
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
for job_id, name in (
    ("firmware_quality", "Firmware coverage + cppcheck"),
    ("soft_ble_e2e", "Soft-BLE DplsClient ↔ simulator"),
    ("firmware_gcc", "PHY6252 GNU Arm GCC"),
    ("firmware_keil", "PHY6252 Keil MDK / AC6"),
    ("ios", "iOS adapter + Xcode host"),
):
    old_shape = f"  {job_id}:\n    name: {name}\n    if: github.event_name != 'pull_request'"
    if old_shape in ci:
        raise SystemExit(f"CI release contract regressed: {job_id} disabled on every PR")

# The final RC gate must depend on every independent evidence source. This is
# intentionally textual: changing the workflow topology requires consciously
# changing this contract in the same review.
release_needs = (
    "      - smoke\n"
    "      - android\n"
    "      - firmverse\n"
    "      - firmware_quality\n"
    "      - soft_ble_e2e\n"
    "      - firmware_gcc\n"
    "      - firmware_keil\n"
    "      - ios"
)
if release_needs not in ci:
    raise SystemExit("RC production gate no longer depends on the full evidence matrix")

for token in (
    "ENABLE_SANITIZERS",
    "-fsanitize=address,undefined",
    "test_protocol_fuzz",
    "test_durable_settings_powerloss_matrix",
):
    if token not in cmake:
        raise SystemExit(f"host sanitizer/fault contract missing from firmware/CMakeLists.txt: {token}")

for token in (
    "test_ci_contract.py",
    "ENABLE_SANITIZERS=ON",
    "ctest --test-dir",
    "architecture_guard.py",
    "test_differential_replay.py",
):
    if token not in host_gate:
        raise SystemExit(f"host invariant gate missing: {token}")

print("CI production contract: OK")
