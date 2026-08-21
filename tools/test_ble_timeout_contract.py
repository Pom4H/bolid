#!/usr/bin/env python3
"""Fail if a second mobile BLE deadline or timeout race returns."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
FIRMWARE = ROOT / "firmware/phy6252/dpls_phy6252_app.c"
TARGET = ROOT / "firmware/targets/phy6252/source/dplsBLEPeripheral.c"


def constant(path: Path, name: str) -> int:
    text = path.read_text(encoding="utf-8")
    for pattern in (
        rf"\b{name}\s*=\s*([0-9_]+)(?:[uUlL]*)\b",
        rf"^[ \t]*#define[ \t]+{name}[ \t]+([0-9_]+)(?:[uUlL]*)\b",
    ):
        match = re.search(pattern, text, flags=re.MULTILINE)
        if match is not None:
            return int(match.group(1).replace("_", ""))
    raise SystemExit(f"{path.relative_to(ROOT)}: constant {name} not found")


# Диагностический radio probe вообще не использует DPLS link/session timeout.
# Не блокируем из-за него target build: смысл этой ветки — проверить только
# reset -> OSAL -> GAP -> advertising на реальной PHY6252.
if "BOLID-BOOT-PROBE" in TARGET.read_text(encoding="utf-8"):
    print("BLE timeout contract: SKIP for minimal radio boot probe")
    raise SystemExit(0)

android = ANDROID.read_text(encoding="utf-8")
for forbidden in ("PAIRING_TIMEOUT_MS", "pairingTimeout"):
    if forbidden in android:
        raise SystemExit(f"Android transport must not own a second connection deadline: {forbidden}")

connect_ms = constant(CLIENT, "CONNECT_TIMEOUT_MS")
firmware_ms = constant(FIRMWARE, "DPLS_LINK_ENCRYPT_TIMEOUT_MS")
if connect_ms >= firmware_ms or firmware_ms - connect_ms < 5_000:
    raise SystemExit(
        f"BLE timeout ordering broken: mobile={connect_ms}ms firmware={firmware_ms}ms"
    )

client = CLIENT.read_text(encoding="utf-8")
subscribed = client[client.index("override fun onSubscribed"):client.index("override fun onBytes")]
if "armConnectTimeout()" not in subscribed:
    raise SystemExit("DplsClient must restart its single deadline after CCCD subscription")

print(f"BLE timeout contract: PASS mobile={connect_ms}ms firmware={firmware_ms}ms")
