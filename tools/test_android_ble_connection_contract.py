#!/usr/bin/env python3
"""Static regression checks for Android BLE recovery/security classification."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
BLE = ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"
text = BLE.read_text(encoding="utf-8")

# GATT 133 is Android's generic stack/controller failure. It may be retried, but
# it is never proof that SMP keys are stale.
if re.search(r"status\s*==\s*GATT_ERROR\s*&&\s*bonded[\s\S]{0,160}onStaleBond", text):
    raise SystemExit("Android BLE: generic GATT 133 must not be classified as stale bond")

# Stale bond remains tied to deterministic protected-write security failure on
# an already bonded peer.
required = (
    "BluetoothDevice.BOND_BONDED -> {",
    "emit { onStaleBond() }",
    "AndroidGattSecurityPolicy.requiresPairing(status)",
    "startPairing(PairingTrigger.RX_WRITE, blocked)",
)
for needle in required:
    if needle not in text:
        raise SystemExit(f"Android BLE contract missing {needle!r}")

print("Android BLE connection contract: PASS")
