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

    # Manual mode запускает тот же vendor programmer, но глушит только RTS/DTR.
    assert "connect_without_control_lines" in flash
    assert "self._port.setRTS = lambda _value: None" in flash
    assert "self._port.setDTR = lambda _value: None" in flash

    # Auto-RST остаётся штатной последовательностью PHY62x2 programmer.
    assert "START_BAUD = 9600" in programmer
    assert "self._port.setRTS(True)" in programmer
    assert "self._port.setDTR(True)" in programmer
    assert "pkt = 'UXTDWU'" in programmer
    assert "read == b'cmd>>:'" in programmer

    print("PHY6252 flasher: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
