#!/usr/bin/env python3
"""Differential wire replay: Python reference framing vs production C simulator.

The test intentionally uses only public protocol observations (type, sequence,
CRC validity and stable challenge fields). Random session material is not golden
text: the C implementation may change its RNG while preserving the wire contract.
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from dpls_wire import FLAG_REQUEST, decode_frame, encode_frame  # noqa: E402

MSG_HELLO = 0x01
MSG_AUTH_CHALLENGE = 0x02
MSG_IDENTIFY_START = 0x14
MSG_IDENTIFY_STOP = 0x15
FACTORY_SALT = bytes(range(0x40, 0x50))


def simulator_path() -> Path:
    override = os.environ.get("DPLS_SIMULATOR")
    if override:
        return Path(override)
    return Path("firmware/build/dpls_simulator")


def main() -> int:
    simulator = simulator_path()
    if not simulator.is_file():
        print(f"simulator not found: {simulator}", file=sys.stderr)
        return 2

    proc = subprocess.Popen(
        [str(simulator)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    assert proc.stdin is not None and proc.stdout is not None

    def command(text: str) -> list[str]:
        proc.stdin.write(text + "\n")
        proc.stdin.flush()
        lines: list[str] = []
        while True:
            raw = proc.stdout.readline()
            if raw == "":
                raise AssertionError(f"simulator exited while handling {text!r}")
            line = raw.rstrip("\n")
            lines.append(line)
            if line == "DONE":
                return lines

    def decoded_tx(lines: list[str]):
        frames = []
        for line in lines:
            if not line.startswith("TX "):
                continue
            raw = bytes.fromhex(line[3:])
            frame = decode_frame(raw)
            assert frame is not None, f"C simulator emitted invalid wire frame: {line}"
            frames.append(frame)
        return frames

    banner = proc.stdout.readline().rstrip("\n")
    assert banner == "READY DPLS2", banner

    command("CONNECT")
    command("CCCD 3")
    command("ENCRYPT 1")

    hello = encode_frame(MSG_HELLO, 10, bytes(16), flags=FLAG_REQUEST)
    assert decode_frame(hello) is not None
    hello_lines = command(f"FRAME {hello.hex().upper()}")
    assert "ACCEPT 1" in hello_lines
    challenge_frames = decoded_tx(hello_lines)
    assert len(challenge_frames) == 1, hello_lines
    challenge = challenge_frames[0]
    assert challenge.msg_type == MSG_AUTH_CHALLENGE
    assert challenge.sequence == 10
    assert len(challenge.payload) == 37
    assert challenge.payload[20:36] == FACTORY_SALT
    assert challenge.payload[36] == 1  # initialized settings

    # Notification 0x03 has no ATT CFM. Advance the same 80 ms pacing boundary
    # before the next request so queued responses cannot hide behind in-flight TX.
    command("TICK 80")

    identify = encode_frame(MSG_IDENTIFY_START, 11, b"", flags=FLAG_REQUEST)
    identify_lines = command(f"FRAME {identify.hex().upper()}")
    assert "ACCEPT 1" in identify_lines
    identify_frames = decoded_tx(identify_lines)
    if not identify_frames:
        identify_lines += command("TICK 80")
        identify_frames = decoded_tx(identify_lines)
    assert len(identify_frames) == 1, identify_lines
    identify_response = identify_frames[0]
    assert identify_response.msg_type == MSG_IDENTIFY_START
    assert identify_response.sequence == 11
    assert identify_response.payload == b""

    # Both implementations must reject the same CRC-corrupted request. The radio
    # queue may accept the ATT value, but the protocol core must emit no response.
    command("TICK 80")
    corrupt = bytearray(encode_frame(MSG_IDENTIFY_STOP, 12, b"", flags=FLAG_REQUEST))
    corrupt[-1] ^= 0x01
    assert decode_frame(bytes(corrupt)) is None
    corrupt_lines = command(f"FRAME {bytes(corrupt).hex().upper()}")
    corrupt_lines += command("TICK 80")
    assert not decoded_tx(corrupt_lines), corrupt_lines

    command("DISCONNECT")
    proc.stdin.write("QUIT\n")
    proc.stdin.flush()
    try:
        proc.wait(timeout=2)
    except subprocess.TimeoutExpired:
        proc.kill()
        raise AssertionError("simulator did not exit")
    assert proc.returncode == 0

    print("OK: differential wire replay")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
