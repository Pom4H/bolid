#!/usr/bin/env python3
"""Regression test: PHY6252 flashing is exactly one application `wh`."""

from __future__ import annotations

import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FLASH = ROOT / "tools" / "flash_firmware.sh"


def main() -> int:
    source = FLASH.read_text(encoding="utf-8")
    assert "factory" not in source.lower()
    assert "0x3F000" not in source
    assert " -r we " not in source
    assert 'ARGS=(-p "$PORT" -r wh "$HEX")' in source

    with tempfile.TemporaryDirectory() as tmp_raw:
        tmp = Path(tmp_raw)
        app = tmp / "output.hex"
        log = tmp / "python-calls.log"
        fake_bin = tmp / "bin"
        fake_bin.mkdir()
        app.write_text(":00000001FF\n", encoding="ascii")

        fake_python = fake_bin / "python3"
        fake_python.write_text(
            "#!/bin/sh\n"
            "printf '%s\\n' \"$*\" >> \"$DPLS_TEST_PYTHON_LOG\"\n"
            "exit 0\n",
            encoding="utf-8",
        )
        fake_python.chmod(0o755)

        env = os.environ.copy()
        env.update(
            {
                "PATH": f"{fake_bin}:{env['PATH']}",
                "PORT": "COM_TEST",
                "DPLS_NO_FLASH_PROMPT": "1",
                "DPLS_TEST_PYTHON_LOG": str(log),
            }
        )
        result = subprocess.run(
            ["bash", str(FLASH), str(app)],
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            raise AssertionError(
                f"flash_firmware.sh failed ({result.returncode})\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}"
            )

        calls = log.read_text(encoding="utf-8").splitlines()
        assert len(calls) == 1, calls
        assert f" -p COM_TEST -r wh {app}" in f" {calls[0]}"
        assert " we " not in calls[0]

    print("PHY6252 single-stage flashing contract: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
