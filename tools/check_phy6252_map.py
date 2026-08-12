#!/usr/bin/env python3
"""Fail if the PHY6252 retained image grows outside SRAM0.

The production target intentionally retains only RET_SRAM0 (32 KiB,
0x1fff0000..0x1fff7fff). ER_IROM1 starts after the ROM jump/config area at
0x1fff1838 and includes all retained RO/RW/ZI plus the stack. A successful link
is therefore not enough: any ER_IROM1 end >= 0x1fff8000 would compile but lose
live state on the next sleep/wake cycle.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

EXPECTED_BASE = 0x1FFF1838
SRAM0_END_EXCLUSIVE = 0x1FFF8000
MIN_HEADROOM = 0x80  # keep at least 128 bytes against harmless linker drift


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: check_phy6252_map.py <test-dpls.axf.map>")

    path = Path(sys.argv[1])
    text = path.read_text(encoding="utf-8", errors="replace")
    match = re.search(
        r"Execution Region ER_IROM1 \(Exec base: 0x([0-9a-fA-F]+),.*?Size: 0x([0-9a-fA-F]+)",
        text,
    )
    if not match:
        raise SystemExit("PHY6252 MAP gate: ER_IROM1 execution region not found")

    base = int(match.group(1), 16)
    size = int(match.group(2), 16)
    end = base + size
    headroom = SRAM0_END_EXCLUSIVE - end

    if base != EXPECTED_BASE:
        raise SystemExit(
            f"PHY6252 MAP gate: ER_IROM1 base moved: 0x{base:08x} != 0x{EXPECTED_BASE:08x}"
        )
    if end > SRAM0_END_EXCLUSIVE:
        raise SystemExit(
            "PHY6252 MAP gate: retained image crosses into unretained SRAM1: "
            f"end=0x{end:08x}, SRAM0 end=0x{SRAM0_END_EXCLUSIVE:08x}"
        )
    if headroom < MIN_HEADROOM:
        raise SystemExit(
            "PHY6252 MAP gate: SRAM0 retention headroom too small: "
            f"{headroom} bytes < {MIN_HEADROOM} bytes"
        )

    print(
        "PHY6252 retained MAP OK: "
        f"ER_IROM1=0x{base:08x}..0x{end - 1:08x}, headroom={headroom} B"
    )


if __name__ == "__main__":
    main()
