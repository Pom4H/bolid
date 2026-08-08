#!/usr/bin/env python3
"""Host-side CRC/framing smoke test shared by Android and iOS clients.

Mirrors Firmware/src/dpls_protocol.c and TestDPLS DplsProtocol known-answers.
"""
from __future__ import annotations

import struct
import sys


VERSION = 1
OVERHEAD = 9


def crc16_ccitt_false(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if (crc & 0x8000) else (crc << 1)
            crc &= 0xFFFF
    return crc


def encode(msg_type: int, sequence: int, payload: bytes = b"", flags: int = 0) -> bytes:
    header = bytes([VERSION, msg_type, flags]) + struct.pack("<HH", sequence, len(payload))
    body = header + payload
    return body + struct.pack("<H", crc16_ccitt_false(body))


def main() -> int:
    assert crc16_ccitt_false(b"123456789") == 0x29B1

    frame = encode(0x12, 0x1234, bytes([0x11, 0x22, 0x33, 0x44, 0x55]))
    assert len(frame) == OVERHEAD + 5
    assert frame[0] == VERSION
    assert frame[1] == 0x12
    assert frame[3] == 0x34 and frame[4] == 0x12  # sequence LE

    hello = encode(0x01, 1, b"")
    assert len(hello) == OVERHEAD
    assert hello[3] == 1 and hello[4] == 0

    print("OK: protocol CRC/framing known-answers")
    return 0


if __name__ == "__main__":
    sys.exit(main())
