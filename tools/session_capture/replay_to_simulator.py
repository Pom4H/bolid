#!/usr/bin/env python3
"""Replay captured phone→device FRAME writes into host dpls_simulator.

This is a scaffolding step toward simulator fidelity: it feeds CONNECT + each
TX hex from a parsed session, prints ACCEPT/TX/MODE/LED/STATE side-effects, and
optionally compares RX frames by type.

Usage:
  cmake --build firmware/build --target dpls_simulator
  python3 tools/session_capture/replay_to_simulator.py \\
      tmp/sessions/session-….frames.txt \\
      --simulator firmware/build/dpls_simulator
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

from dpls_wire import decode_frame, parse_hex


def read_tx_frames(path: Path) -> list[str]:
    frames: list[str] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        if parts[0] == "TX":
            hex_part = parts[-1]
            if parse_hex(hex_part) is None:
                continue
            frames.append(hex_part.upper())
        elif parts[0] == "FRAME":
            hex_part = parts[1]
            if parse_hex(hex_part) is not None:
                frames.append(hex_part.upper())
    return frames


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("frames_file", type=Path)
    parser.add_argument(
        "--simulator",
        type=Path,
        default=Path("firmware/build/dpls_simulator"),
    )
    parser.add_argument("--tick-ms", type=int, default=0, help="optional TICK between frames")
    args = parser.parse_args()

    if not args.simulator.is_file():
        print(f"simulator not found: {args.simulator}", file=sys.stderr)
        return 2

    tx_frames = read_tx_frames(args.frames_file)
    if not tx_frames:
        print("no TX frames to replay", file=sys.stderr)
        return 1

    proc = subprocess.Popen(
        [str(args.simulator)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    assert proc.stdin is not None and proc.stdout is not None

    def cmd(line: str) -> list[str]:
        assert proc.stdin is not None and proc.stdout is not None
        proc.stdin.write(line + "\n")
        proc.stdin.flush()
        out: list[str] = []
        while True:
            got = proc.stdout.readline()
            if got == "":
                break
            text = got.rstrip("\n")
            out.append(text)
            if text == "DONE":
                break
        return out

    banner = proc.stdout.readline().rstrip("\n")
    print(banner)
    if not banner.startswith("READY"):
        print("unexpected simulator banner", file=sys.stderr)
        proc.kill()
        return 1

    for line in cmd("CONNECT"):
        print(line)
    notify_mode = True
    for line in cmd("CCCD 3"):
        print(line)
    for line in cmd("LAB"):
        print(line)

    matched = 0
    for index, hex_frame in enumerate(tx_frames, start=1):
        raw = parse_hex(hex_frame)
        decoded = decode_frame(raw) if raw is not None else None
        label = decoded.type_name if decoded is not None else "?"
        print(f"# {index}/{len(tx_frames)} TX {label} {hex_frame}")
        got_tx = False
        for line in cmd(f"FRAME {hex_frame}"):
            print(line)
            if line.startswith("TX "):
                matched += 1
                got_tx = True
        # Indicate-only CCCD waits for ATT CFM. Samsung writes 0x03 (notify+
        # indicate); firmware then uses GATT_Notification and completes TX
        # without CONFIRM — matching the lab UART (DPLS TX notify=1, no CFM).
        if got_tx and not notify_mode:
            while got_tx:
                got_tx = False
                for line in cmd("CONFIRM"):
                    print(line)
                    if line.startswith("TX "):
                        matched += 1
                        got_tx = True
        if args.tick_ms > 0:
            for line in cmd(f"TICK {args.tick_ms}"):
                print(line)

    for line in cmd("DISCONNECT"):
        print(line)
    proc.stdin.write("QUIT\n")
    proc.stdin.flush()
    try:
        proc.wait(timeout=2)
    except subprocess.TimeoutExpired:
        proc.kill()
    print(f"replayed_tx={len(tx_frames)} simulator_indications={matched}")
    return 0


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    raise SystemExit(main())
