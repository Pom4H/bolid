#!/usr/bin/env python3
"""Инварианты PHY6252 flasher: проверенный manual KEY1 + vendor backend."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
FLASH = ROOT / "tools/flash_firmware.sh"
OLD_AGENT = ROOT / "tools/flash_firmware_agent.sh"
PROGRAMMER = ROOT / "third_party/phy62x2/Utils/rdwr_phy62x2.py"


def main() -> int:
    flash = FLASH.read_text(encoding="utf-8")
    programmer = PROGRAMMER.read_text(encoding="utf-8")

    subprocess.run(["bash", "-n", str(FLASH)], check=True)

    assert not OLD_AGENT.exists()

    # RC8 intentionally uses the same simple flashing path proven on hardware
    # in rc3..rc5. The PB-03F kit does not wire RTS/DTR to reset/test-mode, so
    # pretending that UART can auto-reset the chip is a regression, not a feature.
    assert "--auto-rst" not in flash
    assert "setRTS" not in flash
    assert "setDTR" not in flash
    assert "controlled_connect" not in flash
    assert "enter_rom" not in flash
    assert "post-flash readback" not in flash

    # One operation: user enters ROM with KEY1 and the untouched vendor utility
    # performs the regular write-HEX command with reset afterwards.
    assert 'HEX="${1:?usage: flash_firmware.sh <file.hex> [--erase]}"' in flash
    assert 'ARGS=(-p "$PORT" -r wh "$HEX")' in flash
    assert "зажмите KEY1" in flash
    assert "Turn on the power" in flash
    assert 'exec python3 "$ROOT/third_party/phy62x2/Utils/rdwr_phy62x2.py" "${ARGS[@]}"' in flash

    # Normal flashing must preserve SNV/factory data. Full-chip erase requires
    # a deliberate second opt-in in addition to the command-line flag.
    assert 'if [ "${2:-}" = "--erase" ]' in flash
    assert "DPLS_ALLOW_FACTORY_ERASE" in flash
    assert "factory identity" in flash
    assert 'ARGS=(-p "$PORT" -a -r wh "$HEX")' in flash

    # Vendor source remains the only source of ROM/flash protocol behaviour.
    assert "START_BAUD = 9600" in programmer
    assert "DEF_RUN_BAUD = 115200" in programmer
    assert "def FlashUnlock" in programmer
    assert "'wh'" in programmer
    assert "ParseHexFile" in programmer

    print("PHY6252 flasher: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
