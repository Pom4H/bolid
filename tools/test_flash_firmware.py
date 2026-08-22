#!/usr/bin/env python3
"""Инварианты PHY6252 flasher: manual KEY1, safe erase и recovery identity."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
FLASH = ROOT / "tools/flash_firmware.sh"
OLD_AGENT = ROOT / "tools/flash_firmware_agent.sh"
PROGRAMMER = ROOT / "third_party/phy62x2/Utils/rdwr_phy62x2.py"
IDENTITY = ROOT / "firmware/phy6252/dpls_ble_identity.c"


def main() -> int:
    flash = FLASH.read_text(encoding="utf-8")
    programmer = PROGRAMMER.read_text(encoding="utf-8")
    identity = IDENTITY.read_text(encoding="utf-8")

    subprocess.run(["bash", "-n", str(FLASH)], check=True)

    assert not OLD_AGENT.exists()

    # PB-03F kit does not wire RTS/DTR to reset/test-mode, so the application
    # wrapper owns only the proven manual KEY1 path.
    assert "--auto-rst" not in flash
    assert "setRTS" not in flash
    assert "setDTR" not in flash
    assert "controlled_connect" not in flash
    assert "enter_rom" not in flash
    assert "post-flash readback" not in flash

    # Normal flashing is one vendor write-HEX operation with reset afterwards.
    assert 'HEX="${1:?usage: flash_firmware.sh <file.hex> [--erase]}"' in flash
    assert 'exec python3 "$FLASHER" -p "$PORT" -r wh "$HEX"' in flash
    assert "зажмите KEY1" in flash
    assert "Turn on the power" in flash

    # --erase means clearing only the product SNV work area. A physical chip
    # erase destroys PHY6252 factory MAC/ChipID words and is never exposed by
    # the application wrapper. The second command continues the same ROM session.
    assert 'if [ "$MODE" = "--erase" ]' in flash
    assert 'er 0x3C000 0x3000' in flash
    assert 'exec python3 "$FLASHER" -p "$PORT" -n -r wh "$HEX"' in flash
    assert 'DPLS_ALLOW_FACTORY_ERASE' not in flash
    assert 'ARGS=(-p "$PORT" -a -r wh "$HEX")' not in flash
    assert 'cmd_erase_all_flash' not in flash

    # Vendor utility itself documents why -a/ea is forbidden here.
    assert "--allerase" in programmer
    assert "Erase All Flash (MAC, ChipID/IV)" in programmer
    assert "START_BAUD = 9600" in programmer
    assert "DEF_RUN_BAUD = 115200" in programmer
    assert "def FlashUnlock" in programmer
    assert "'wh'" in programmer
    assert "ParseHexFile" in programmer

    # Boards erased by older tooling remain recoverable: when the factory MAC
    # words are gone, firmware derives a stable static-random identity from the
    # flash-die Unique ID instead of publishing device_id=0.
    for token in (
        "DPLS_FLASH_UID_CMD 0x4bu",
        "read_flash_unique_id",
        "build_recovery_identity",
        "uid_device_id",
        "ADDRTYPE_STATIC",
    ):
        assert token in identity, token

    print("PHY6252 flasher: PASS")
    print("  --erase: SNV only; MAC/ChipID/factory sectors preserved")
    print("  erased prototype recovery: Flash Unique ID -> static BLE identity")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
