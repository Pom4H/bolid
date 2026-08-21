#!/usr/bin/env python3
"""PHY6252 flasher остаётся одним, простым и неинтерактивным."""
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
    assert "--auto-rst" in flash
    assert 'HEX="$ROOT/tmp/test-dpls.hex"' in flash
    assert 'ARGS=(-p "$PORT" -r)' in flash
    assert 'ARGS+=(wh "$HEX")' in flash
    assert "read -r" not in flash
    assert "ls /dev/" not in flash
    assert "factory.bin" not in flash.lower()
    assert "0x3F000" not in flash
    assert "pyserial==3.5" in flash

    # Один wrapper меняет только ROM entry; vendor utility остаётся flash backend.
    assert "def enter_rom(self):" in flash
    assert "module.phyflasher.Connect = controlled_connect" in flash
    assert "original_connect(self, module.START_BAUD)" in flash
    assert 'ARGS+=(wh "$HEX")' in flash

    # Manual mode не ждёт Enter и не требует отдельного скрипта.
    assert "ROM entry: MANUAL" in flash
    assert "hold KEY1 and reset/power-cycle" in flash

    # Auto mode: штатная последовательность PHY62x2 + UXTDWU@9600.
    assert "self._port.setRTS(True)" in flash
    assert "self._port.setDTR(True)" in flash
    assert "self._port.setDTR(False)" in flash
    assert "self._port.setRTS(False)" in flash
    assert 'self._port.write(b"UXTDWU")' in flash
    assert "range(250)" in flash
    assert "TX/RX alone cannot reset" in flash

    # Vendor source остаётся источником flash protocol и baud constants.
    assert "START_BAUD = 9600" in programmer
    assert "DEF_RUN_BAUD = 115200" in programmer
    assert "def FlashUnlock" in programmer
    assert "'wh'" in programmer
    assert "ParseHexFile" in programmer

    print("PHY6252 flasher: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
