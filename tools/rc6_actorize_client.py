#!/usr/bin/env python3
"""One-shot mechanical RC6 migration with exact, fail-fast anchors."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
GUARD = ROOT / "tools/architecture_guard.py"


def replace_once_or_done(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}: {old!r}")
    return text.replace(old, new)


client = CLIENT.read_text(encoding="utf-8")
client = replace_once_or_done(
    client,
    "    private var session: DeviceSession = DeviceSession.Offline\n",
    "    private val connection = ConnectionActor()\n"
    "    private val session: DeviceSession\n"
    "        get() = connection.state\n",
    "DplsClient owner",
)
client = replace_once_or_done(
    client,
    "    private fun setSession(next: DeviceSession) {\n"
    "        session = next\n"
    "        mutableState.value = projectSession(mutableState.value)\n"
    "    }\n",
    "    private fun setSession(next: DeviceSession) {\n"
    "        connection.transitionTo(next)\n"
    "        mutableState.value = projectSession(mutableState.value)\n"
    "    }\n",
    "DplsClient transition",
)
CLIENT.write_text(client, encoding="utf-8")

guard = GUARD.read_text(encoding="utf-8")
guard = replace_once_or_done(
    guard,
    'CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"\n',
    'CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"\n'
    'CONNECTION_ACTOR = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/ConnectionActor.kt"\n'
    'CONNECTION_MACHINE = ROOT / "mobile/runtime/src/commonMain/kotlin/ru/bolid/testdpls/core/runtime/ConnectionMachine.kt"\n',
    "architecture guard paths",
)
guard = replace_once_or_done(
    guard,
    'require_text(CLIENT, "private var session: DeviceSession", "controller must have one lifecycle owner")\n',
    'require_text(CLIENT, "private val connection = ConnectionActor()", "ConnectionActor must own product lifecycle")\n'
    'require_text(CLIENT, "get() = connection.state", "DplsClient may only project actor state")\n'
    'forbid_regex(CLIENT, r"private\\s+var\\s+session\\s*:\\s*DeviceSession", "mutable DeviceSession copy is forbidden")\n'
    'require_text(CLIENT, "connection.transitionTo(next)", "legacy transition bridge must pass through reducer")\n'
    'require_text(CONNECTION_ACTOR, "ConnectionMachine.reduce(state, event)", "actor must delegate every transition to pure reducer")\n'
    'require_regex(CONNECTION_MACHINE, r"fun\\s+reduce\\s*\\(state:\\s*DeviceSession,\\s*event:\\s*ConnectionEvent\\)", "connection reducer must remain pure and explicit")\n',
    "lifecycle ownership guard",
)
guard = replace_once_or_done(
    guard,
    r'require_regex(PHY_TARGET, r"dpls_phy6252_snv_disconnect_requested\\s*\\(\\s*\\)\\s*&&\\s*dpls_phy6252_tx_idle\\s*\\(\\s*\\)", "deferred flash disconnect must wait for TX drain")\n',
    r'require_regex(PHY_TARGET, r"dpls_phy6252_flash_disconnect_requested\\s*\\(\\s*\\)\\s*&&\\s*dpls_phy6252_tx_idle\\s*\\(\\s*\\)", "storage actor disconnect must wait for TX drain")\n',
    "storage target guard",
)
GUARD.write_text(guard, encoding="utf-8")
print("RC6 actor ownership migration applied")
