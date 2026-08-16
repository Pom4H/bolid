#!/usr/bin/env python3
"""Minimal DPLS wire helpers for session capture/replay (version 2 frames)."""
from __future__ import annotations

import struct
from dataclasses import dataclass

VERSION = 2
HEADER_SIZE = 7
TRAILER_SIZE = 2
OVERHEAD = HEADER_SIZE + TRAILER_SIZE

TYPE_NAMES = {
    0x01: "HELLO",
    0x02: "AUTH_CHALLENGE",
    0x03: "AUTH_PROOF",
    0x04: "AUTH_RESULT",
    0x05: "SETUP",
    0x06: "DEVICE_INFO_GET",
    0x07: "DEVICE_INFO_REPORT",
    0x08: "NAME_SET",
    0x09: "PASSWORD_SET",
    0x0A: "SETTINGS_RESULT",
    0x0B: "TIME_SYNC",
    0x10: "STATE_GET",
    0x11: "STATE_REPORT",
    0x12: "MODE_SET",
    0x13: "COMMAND_RESULT",
    0x14: "IDENTIFY_START",
    0x15: "IDENTIFY_STOP",
    0x20: "LOG_START",
    0x21: "LOG_INFO",
    0x22: "LOG_CHUNK",
    0x23: "LOG_ACK",
    0x24: "LOG_FINISH",
    0x25: "LOG_RESULT",
    0x26: "LOG_HIST_GET",
    0x27: "LOG_HIST_REPORT",
    0x30: "KEEP_ALIVE",
    0x7F: "ERROR",
}

FLAG_REQUEST = 1 << 0
FLAG_RESPONSE = 1 << 1
FLAG_EVENT = 1 << 2
FLAG_ERROR = 1 << 3


def crc16_ccitt_false(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if (crc & 0x8000) else (crc << 1)
            crc &= 0xFFFF
    return crc


@dataclass(frozen=True)
class Frame:
    version: int
    msg_type: int
    flags: int
    sequence: int
    payload: bytes

    @property
    def type_name(self) -> str:
        return TYPE_NAMES.get(self.msg_type, f"TYPE_0x{self.msg_type:02X}")

    @property
    def is_request(self) -> bool:
        return bool(self.flags & FLAG_REQUEST)

    @property
    def is_response(self) -> bool:
        return bool(self.flags & FLAG_RESPONSE)


def decode_frame(raw: bytes) -> Frame | None:
    if len(raw) < OVERHEAD:
        return None
    version, msg_type, flags, sequence, length = struct.unpack_from("<BBBHH", raw, 0)
    if version not in (1, 2):
        return None
    if length > len(raw) - OVERHEAD:
        return None
    body = raw[: HEADER_SIZE + length]
    trailer = struct.unpack_from("<H", raw, HEADER_SIZE + length)[0]
    if trailer != crc16_ccitt_false(body):
        return None
    return Frame(version, msg_type, flags, sequence, raw[HEADER_SIZE : HEADER_SIZE + length])


def encode_frame(msg_type: int, sequence: int, payload: bytes = b"", flags: int = FLAG_REQUEST) -> bytes:
    header = bytes([VERSION, msg_type, flags]) + struct.pack("<HH", sequence, len(payload))
    body = header + payload
    return body + struct.pack("<H", crc16_ccitt_false(body))


def parse_hex(text: str) -> bytes | None:
    cleaned = "".join(ch for ch in text if not ch.isspace())
    if len(cleaned) % 2 != 0:
        return None
    try:
        return bytes.fromhex(cleaned)
    except ValueError:
        return None
