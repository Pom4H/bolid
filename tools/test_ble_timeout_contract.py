#!/usr/bin/env python3
"""Fail if mobile connection timing becomes multi-owner again."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"
CLIENT = ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsClient.kt"
FIRMWARE = ROOT / "firmware/phy6252/dpls_phy6252_app.c"


def constant(path: Path, name: str) -> int:
    text = path.read_text(encoding="utf-8")
    patterns = (
        rf"\b{name}\s*=\s*([0-9_]+)(?:[uUlL]*)\b",
        rf"^[ \t]*#define[ \t]+{name}[ \t]+([0-9_]+)(?:[uUlL]*)\b",
    )
    for pattern in patterns:
        match = re.search(pattern, text, flags=re.MULTILINE)
        if match is not None:
            return int(match.group(1).replace("_", ""))
    raise SystemExit(f"{path.relative_to(ROOT)}: constant {name} not found")


android = ANDROID.read_text(encoding="utf-8")
client = CLIENT.read_text(encoding="utf-8")

# Android GATT is an adapter. It may poll bond state, but it must not own a
# second human-facing pairing deadline competing with the product attempt.
for forbidden in ("PAIRING_TIMEOUT_MS", "pairingTimeout: Runnable", "pairing timeout state="):
    if forbidden in android:
        raise SystemExit(f"Android transport owns forbidden pairing deadline: {forbidden}")
if "PAIRING_POLL_MS" not in android:
    raise SystemExit("Android transport must retain bond-state polling fallback")

connect_ms = constant(CLIENT, "CONNECT_TIMEOUT_MS")
firmware_ms = constant(FIRMWARE, "DPLS_LINK_ENCRYPT_TIMEOUT_MS")
if not connect_ms < firmware_ms:
    raise SystemExit(
        f"BLE timeout ordering broken: expected client < firmware, got {connect_ms} < {firmware_ms}"
    )
if firmware_ms - connect_ms < 5_000:
    raise SystemExit("BLE client/firmware timeout margin must be at least 5 s")

# Discovery gets one attempt budget, and subscription deliberately starts a new
# budget for HELLO/SMP/auth so slow GATT discovery cannot steal pairing time.
if client.count("armConnectTimeout()") < 2:
    raise SystemExit("DplsClient must arm connection deadline at connect and re-arm after subscription")

print(
    "BLE timeout contract: PASS "
    f"single-mobile-deadline={connect_ms}ms firmware={firmware_ms}ms"
)
