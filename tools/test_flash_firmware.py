#!/usr/bin/env python3
"""PHY6252 flash wrappers stay simple and non-interactive."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HUMAN = ROOT / "tools/flash_firmware.sh"
AGENT = ROOT / "tools/flash_firmware_agent.sh"
PROGRAMMER = ROOT / "third_party/phy62x2/Utils/rdwr_phy62x2.py"


def main() -> int:
    human = HUMAN.read_text(encoding="utf-8")
    agent = AGENT.read_text(encoding="utf-8")
    programmer = PROGRAMMER.read_text(encoding="utf-8")

    for source in (human, agent):
        assert 'ARGS=(-p "$PORT" -r wh "$HEX")' in source
        assert "factory.bin" not in source.lower()
        assert "0x3F000" not in source
        assert " -r we " not in source
        assert "read -r" not in source

    assert "DPLS_NO_FLASH_PROMPT" not in human
    assert "UXTDWU" in human
    assert "UXTDWU" in agent
    assert "9600" in agent

    # The actual ROM-entry sequence belongs to the vendored PHY62x2 programmer.
    assert "START_BAUD = 9600" in programmer
    assert "self._port.setRTS(True)" in programmer
    assert "self._port.setDTR(True)" in programmer
    assert "pkt = 'UXTDWU'" in programmer
    assert "read == b'cmd>>:'" in programmer

    print("PHY6252 flash wrappers: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
