#!/usr/bin/env python3
"""Fail if BLE security/watchdog budgets can race each other again."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
FIRMWARE = ROOT / "firmware/phy6252/dpls_phy6252_app.c"


def constant(path: Path, name: str) -> int:
    text = path.read_text(encoding="utf-8")
    match = re.search(rf"\b{name}\s*=\s*([0-9_]+)(?:[Lu]*)\b", text)
    if match is None:
        raise SystemExit(f"{path.relative_to(ROOT)}: constant {name} not found")
    return int(match.group(1).replace("_", ""))


pairing_ms = constant(ANDROID, "PAIRING_TIMEOUT_MS")
connect_ms = constant(CLIENT, "CONNECT_TIMEOUT_MS")
firmware_ms = constant(FIRMWARE, "DPLS_LINK_ENCRYPT_TIMEOUT_MS")

if not pairing_ms < connect_ms < firmware_ms:
    raise SystemExit(
        "BLE timeout ordering broken: expected "
        f"pairing < client < firmware, got {pairing_ms} < {connect_ms} < {firmware_ms}"
    )

# Keep enough separation that independently scheduled callbacks cannot win on
# the same scheduler turn merely because of handler/coroutine jitter.
if connect_ms - pairing_ms < 5_000:
    raise SystemExit("BLE pairing/client timeout margin must be at least 5 s")
if firmware_ms - connect_ms < 5_000:
    raise SystemExit("BLE client/firmware timeout margin must be at least 5 s")

print(
    "BLE timeout contract: PASS "
    f"pairing={pairing_ms}ms client={connect_ms}ms firmware={firmware_ms}ms"
)
