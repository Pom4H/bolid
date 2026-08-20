#!/usr/bin/env python3
"""Guard the production PHY6252 GNU/AC6 XIP placement contract."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GNU = ROOT / "firmware/targets/phy6252/phy6252.ld"
AC6 = ROOT / "firmware/targets/phy6252/scatter_load.sct"


def require(path: Path, pattern: str, message: str) -> None:
    text = path.read_text(encoding="utf-8")
    if re.search(pattern, text, flags=re.MULTILINE) is None:
        raise SystemExit(f"{path.relative_to(ROOT)}: {message}")


def main() -> int:
    # Every project-owned DPLS object belongs to flash XIP on both production
    # toolchains. Wildcards are intentional: adding a new dpls_*.c must not
    # require a second hand-maintained linker allow-list.
    require(
        GNU,
        r"\*dpls\*\.o\(\.text\*\s+\.rodata\*\)",
        "GNU linker must route dpls*.o text/rodata to XIP",
    )
    require(
        AC6,
        r"^\s*dpls\*\.o\(\+RO\)\s*$",
        "AC6 scatter must route dpls*.o RO to XIP",
    )

    ac6 = AC6.read_text(encoding="utf-8")
    explicit_project_objects = re.findall(r"^\s*(dpls[^*\s]+\.o)\(\+RO\)\s*$", ac6, flags=re.MULTILINE)
    if explicit_project_objects:
        raise SystemExit(
            f"{AC6.relative_to(ROOT)}: explicit DPLS XIP allow-list is forbidden; "
            f"use dpls*.o wildcard (found {', '.join(explicit_project_objects)})"
        )

    print("PHY6252 linker parity: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
