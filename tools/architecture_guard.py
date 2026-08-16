#!/usr/bin/env python3
"""Fail CI on architectural ownership violations.

This is deliberately not a complexity score. It enforces repository-specific
invariants that must be true regardless of formatting: one session owner, one
transaction id, and strict dependency zones.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
SESSION = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/DeviceSession.kt"
SEQUENCER = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/session/DplsSession.kt"

violations: list[str] = []


def fail(path: Path, message: str) -> None:
    violations.append(f"{path.relative_to(ROOT)}: {message}")


def require_text(path: Path, needle: str, message: str) -> None:
    text = path.read_text(encoding="utf-8")
    if needle not in text:
        fail(path, message)


def forbid_text(path: Path, needle: str, message: str) -> None:
    text = path.read_text(encoding="utf-8")
    if needle in text:
        fail(path, message)


def forbid_regex(path: Path, pattern: str, message: str) -> None:
    text = path.read_text(encoding="utf-8")
    if re.search(pattern, text, flags=re.MULTILINE):
        fail(path, message)


# Session/auth truth lives only in DeviceSession. The controller may project it
# into immutable UI snapshots, but must never use those projection fields for
# protocol decisions.
require_text(SESSION, "sealed interface DeviceSession", "DeviceSession must own lifecycle state")
require_text(SESSION, "data class SessionChallenge", "challenge material must live in DeviceSession")
require_text(SESSION, "data class AuthSession", "authenticated wire material must live in DeviceSession")
require_text(CLIENT, "private var session: DeviceSession", "controller must have exactly one lifecycle owner")
require_text(CLIENT, "private fun projectSession", "UI lifecycle fields must be a projection of DeviceSession")

for stale_owner in ("DplsSessionRuntime", "wireSession", "runtimeSession", "selectedAddress"):
    forbid_text(CLIENT, stale_owner, f"second session/route owner is forbidden: {stale_owner}")

for ui_truth in ("state.authenticated", "state.credentialsReady"):
    forbid_text(CLIENT, ui_truth, f"controller must not branch on UI projection {ui_truth}")

for field in ("sessionId", "sessionToken", "clientNonce", "deviceNonce", "authSalt", "authenticated"):
    forbid_regex(
        CLIENT,
        rf"^\s*private\s+(?:var|val)\s+{field}\b",
        f"{field} may not be stored independently in DplsClient",
    )

# The old session object is now intentionally a sequence generator only.
require_text(SEQUENCER, "class FrameSequencer", "wire helper must be FrameSequencer only")
for secret in ("sessionId", "sessionToken", "clientNonce", "deviceNonce", "authSalt"):
    forbid_text(SEQUENCER, secret, f"FrameSequencer must not own session secret {secret}")

# Protocol v2 has exactly one transaction id: Frame.sequence. Legacy decoding
# fields are allowed only in the compatibility parser.
for path in (ROOT / "mobile").rglob("*.kt"):
    if path.name == "DplsControlMessages.kt":
        continue
    forbid_regex(path, r"\bcommandId\b", "second transaction id commandId is forbidden outside v1 decode compatibility")

# Runtime and wire are dependency zones, not product/UI modules.
for path in (ROOT / "mobile/runtime/src/commonMain").rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    for forbidden in ("android.", "androidx.compose", "platform.CoreBluetooth", ".core.domain.", ".core.app."):
        if forbidden in text:
            fail(path, f"runtime dependency leak: {forbidden}")

for path in (ROOT / "mobile/wire/src/commonMain").rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    for forbidden in ("kotlinx.coroutines", "android.", "androidx.compose", "platform.CoreBluetooth", ".core.domain.", ".core.app."):
        if forbidden in text:
            fail(path, f"wire dependency leak: {forbidden}")

# Direct StateFlow replacement is allowed only for a fresh retained UI state or
# through the session projection. This prevents a new mutable lifecycle truth
# from bypassing projectSession().
for number, line in enumerate(CLIENT.read_text(encoding="utf-8").splitlines(), start=1):
    stripped = line.strip()
    if "mutableState.value =" not in stripped:
        continue
    if "projectSession(" in stripped or "retainedUiState(" in stripped or stripped.startswith("private val mutableState"):
        continue
    fail(CLIENT, f"line {number}: direct UI state replacement bypasses session projection")

if violations:
    print("Architecture guard failed:")
    for item in violations:
        print(f"  - {item}")
    raise SystemExit(1)

print("Architecture guard: OK")
print("  session owners: 1 (DeviceSession)")
print("  transaction ids: 1 (Frame.sequence)")
print("  UI lifecycle state: projection only")
print("  wire/runtime dependency zones: clean")
