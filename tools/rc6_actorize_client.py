#!/usr/bin/env python3
"""One-shot mechanical RC6 migration: make ConnectionActor own DeviceSession."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
text = PATH.read_text(encoding="utf-8")

replacements = {
    "    private var session: DeviceSession = DeviceSession.Offline\n": (
        "    private val connection = ConnectionActor()\n"
        "    private val session: DeviceSession\n"
        "        get() = connection.state\n"
    ),
    "    private fun setSession(next: DeviceSession) {\n"
    "        session = next\n"
    "        mutableState.value = projectSession(mutableState.value)\n"
    "    }\n": (
        "    private fun setSession(next: DeviceSession) {\n"
        "        connection.transitionTo(next)\n"
        "        mutableState.value = projectSession(mutableState.value)\n"
        "    }\n"
    ),
}

for old, new in replacements.items():
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one migration anchor, found {count}: {old!r}")
    text = text.replace(old, new)

PATH.write_text(text, encoding="utf-8")
print("DplsClient lifecycle actorized")
