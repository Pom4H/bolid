#!/usr/bin/env python3
"""Keep host soft-BLE adapters aligned with the measured PHY6252 model.

These numbers cross language/process boundaries, so sharing one runtime constant
would be fake reuse. The C PHY model remains authoritative and this gate makes
its intentionally repeated stdio-adapter literals fail together when it changes.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PHY = ROOT / "firmware/phy6252_emu/phy6252_emu.h"
JVM = ROOT / "mobile/interop/src/jvmTest/kotlin/ru/bolid/testdpls/interop/SimulatorBleTransport.kt"
HUB = ROOT / "tools/dpls-lab/hub.ts"
SIM_BOARD = ROOT / "firmware/sim/dpls_sim_board.h"


def source(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def define(text: str, name: str) -> int:
    match = re.search(rf"^#define\s+{re.escape(name)}\s+(0x[0-9A-Fa-f]+|\d+)u?\s*$", text, re.MULTILINE)
    if match is None:
        raise AssertionError(f"missing {name} in {PHY.relative_to(ROOT)}")
    return int(match.group(1), 0)


def require(text: str, needle: str, where: Path) -> None:
    if needle not in text:
        raise AssertionError(f"{where.relative_to(ROOT)} must contain {needle!r}")


def main() -> int:
    phy = source(PHY)
    pace_ms = define(phy, "PHY6252_EMU_NOTIFY_PACE_MS")
    samsung_cccd = define(phy, "PHY6252_EMU_CCCD_SAMSUNG")

    jvm = source(JVM)
    require(jvm, f'"TICK {pace_ms}"', JVM)
    require(jvm, f'"CCCD {samsung_cccd}"', JVM)
    require(jvm, "const val WRITE_LIMIT = DplsProtocol.MAX_FRAME", JVM)

    hub = source(HUB)
    require(hub, f'"TICK {pace_ms}"', HUB)
    require(hub, f'"CCCD {samsung_cccd}"', HUB)

    sim_board = source(SIM_BOARD)
    require(sim_board, "#define DPLS_SIM_TX_NOTIFY_PACE_MS PHY6252_EMU_NOTIFY_PACE_MS", SIM_BOARD)

    print(f"OK: simulator adapters match PHY6252 notify pace ({pace_ms} ms)")
    print(f"OK: simulator adapters match Samsung CCCD (0x{samsung_cccd:04X})")
    print("OK: KMP simulator write limits come from DplsProtocol.MAX_FRAME")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
