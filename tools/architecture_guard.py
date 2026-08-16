#!/usr/bin/env python3
"""Fail CI on a small set of repository-specific ownership violations.

This is deliberately not a complexity analyzer and not a parser. Keep rules here
only when the invariant is narrow enough to detect reliably with source text.
Types, module dependencies and behavioral tests remain the primary architecture.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
SESSION = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/DeviceSession.kt"
SEQUENCER = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/session/DplsSession.kt"
ANDROID_BLE = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"

violations: list[str] = []


def fail(path: Path, message: str) -> None:
    violations.append(f"{path.relative_to(ROOT)}: {message}")


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def require_text(path: Path, needle: str, message: str) -> None:
    if needle not in text(path):
        fail(path, message)


def forbid_text(path: Path, needle: str, message: str) -> None:
    if needle in text(path):
        fail(path, message)


def forbid_regex(path: Path, pattern: str, message: str) -> None:
    if re.search(pattern, text(path), flags=re.MULTILINE):
        fail(path, message)


# DeviceSession is the only owner of link/auth lifecycle. Authentication and
# identity proof are distinct states; Online cannot represent an unknown node.
require_text(SESSION, "sealed interface DeviceSession", "DeviceSession must own lifecycle state")
require_text(SESSION, "data class SessionChallenge", "challenge material must live in DeviceSession")
require_text(SESSION, "data class AuthSession", "authenticated wire material must live in DeviceSession")
require_text(SESSION, "data class Synchronizing(", "authentication must not imply verified identity")
require_text(SESSION, "data class Online(\n        val nodeId: NodeId,", "Online must require a verified non-null NodeId")
forbid_text(SESSION, "data class Online(\n        val nodeId: NodeId?", "Online may not contain an unknown identity")

require_text(CLIENT, "private var session: DeviceSession", "controller must have exactly one lifecycle owner")
require_text(CLIENT, "private fun projectSession", "UI lifecycle fields must be projected from DeviceSession")
require_text(CLIENT, "phase = connectionPhase(ui)", "UI phase must be derived from DeviceSession")

for stale_owner in ("DplsSessionRuntime", "wireSession", "runtimeSession", "selectedAddress"):
    forbid_text(CLIENT, stale_owner, f"second session/route owner is forbidden: {stale_owner}")

for ui_truth in ("state.phase", "state.authenticated", "state.credentialsReady"):
    forbid_text(CLIENT, ui_truth, f"controller must not branch on UI lifecycle projection {ui_truth}")

# A named argument/property assignment `phase = ...` may exist only in the
# projection. Local variables such as `val phase = ...` are unrelated.
for number, line in enumerate(text(CLIENT).splitlines(), start=1):
    if not re.match(r"^\s*phase\s*=", line):
        continue
    if "phase = connectionPhase(ui)" not in line:
        fail(CLIENT, f"line {number}: lifecycle phase must be projected, not assigned")

for field in ("sessionId", "sessionToken", "clientNonce", "deviceNonce", "authSalt", "authenticated"):
    forbid_regex(
        CLIENT,
        rf"^\s*private\s+(?:var|val)\s+{field}\b",
        f"{field} may not be stored independently in DplsClient",
    )

# The wire session helper is intentionally only a sequence generator.
require_text(SEQUENCER, "class FrameSequencer", "wire helper must be FrameSequencer only")
for secret in ("sessionId", "sessionToken", "clientNonce", "deviceNonce", "authSalt"):
    forbid_text(SEQUENCER, secret, f"FrameSequencer must not own session secret {secret}")

# Protocol v2 has exactly one transaction id: Frame.sequence. Legacy v1 decode
# compatibility and its direct tests are the only places where the old name may remain.
legacy_command_id_paths = {
    "DplsControlMessages.kt",
    "DplsControlMessagesTest.kt",
}
for path in (ROOT / "mobile").rglob("*.kt"):
    if path.name in legacy_command_id_paths:
        continue
    forbid_regex(
        path,
        r"\bcommandId\b",
        "second transaction id commandId is forbidden outside v1 decode compatibility",
    )

# Cancellation is cleanup, not identity. Delayed operation work must compare the
# physical-link epoch and exact frame sequence.
require_text(
    CLIENT,
    "if (generation == linkGeneration && operation?.sequence == sequence) action()",
    "operation timeout must be correlated to link epoch and request sequence",
)
for generation in ("linkGeneration", "scanGeneration", "logTimeoutGeneration"):
    require_text(CLIENT, generation, f"missing stale-work generation guard: {generation}")

# Runtime and wire are dependency zones, not product/UI modules.
for path in (ROOT / "mobile/runtime/src/commonMain").rglob("*.kt"):
    source = text(path)
    for forbidden in ("android.", "androidx.compose", "platform.CoreBluetooth", ".core.domain.", ".core.app."):
        if forbidden in source:
            fail(path, f"runtime dependency leak: {forbidden}")

for path in (ROOT / "mobile/wire/src/commonMain").rglob("*.kt"):
    source = text(path)
    for forbidden in ("kotlinx.coroutines", "android.", "androidx.compose", "platform.CoreBluetooth", ".core.domain.", ".core.app."):
        if forbidden in source:
            fail(path, f"wire dependency leak: {forbidden}")

# Android's GATT callback state must be confined to the same main Handler that
# serializes product callbacks. This avoids relying on BLE-stack callback threads.
require_text(
    ANDROID_BLE,
    "BluetoothDevice.PHY_LE_1M_MASK,\n            handler,",
    "connectGatt must deliver callbacks on the main Handler",
)

# Every direct StateFlow replacement must visibly re-apply the lifecycle
# projection. Ordinary mutations go through updateState().
for number, line in enumerate(text(CLIENT).splitlines(), start=1):
    stripped = line.strip()
    if "mutableState.value =" not in stripped:
        continue
    if "projectSession(" in stripped or stripped.startswith("private val mutableState"):
        continue
    fail(CLIENT, f"line {number}: direct UI state replacement bypasses session projection")

if violations:
    print("Architecture guard failed:")
    for item in violations:
        print(f"  - {item}")
    raise SystemExit(1)

print("Architecture guard: OK")
print("  lifecycle/auth owner: DeviceSession")
print("  Online identity: verified NodeId")
print("  transaction id: Frame.sequence")
print("  delayed work: link epoch + sequence/generation guarded")
print("  Android GATT state: main-looper confined")
print("  wire/runtime dependency zones: clean")
