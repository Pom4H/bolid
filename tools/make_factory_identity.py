#!/usr/bin/env python3
"""Создание factory identity для серийного Test-DPLS.

Формирует Intel HEX ровно для выделенного сектора 0x1103F000. По умолчанию
BLE использует заводской public MAC PHY6252, а IRK/CSRK генерируются один раз
на производстве и затем остаются частью неизменяемой идентичности прибора.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import secrets
import struct
from pathlib import Path

FLASH_ADDRESS = 0x1103F000
RECORD_SIZE = 64
MAGIC = 0x31444944  # bytes: DID1
VERSION = 1
FLAG_BLE_STATIC = 0x0001
FLAG_IRK = 0x0002
FLAG_CSRK = 0x0004
BLE_ADDR_CHIP_PUBLIC = 0
BLE_ADDR_STATIC = 1


def crc16_ccitt_false(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def parse_mac(value: str) -> bytes:
    compact = value.replace(":", "").replace("-", "")
    if len(compact) != 12:
        raise argparse.ArgumentTypeError("MAC должен содержать 6 байт")
    try:
        mac = bytes.fromhex(compact)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("MAC должен быть шестнадцатеричным") from exc
    if mac in (b"\x00" * 6, b"\xff" * 6):
        raise argparse.ArgumentTypeError("нулевой/FF MAC недопустим")
    return mac


def generate_static_mac() -> bytes:
    mac = bytearray(secrets.token_bytes(6))
    mac[0] |= 0xC0
    if mac == b"\xff" * 6:
        mac[-1] = 0xFE
    return bytes(mac)


def make_record(serial: int, hw_revision: int, static_mac: bytes | None) -> bytes:
    if not 1 <= serial <= 0xFFFFFFFE:
        raise ValueError("serial должен быть в диапазоне 1..4294967294")
    if not 0 <= hw_revision <= 0xFFFF:
        raise ValueError("hw_revision должен быть в диапазоне 0..65535")

    raw = bytearray(b"\xff" * RECORD_SIZE)
    flags = FLAG_IRK | FLAG_CSRK
    ble_addr_type = BLE_ADDR_CHIP_PUBLIC
    ble_addr = b"\xff" * 6

    if static_mac is not None:
        if static_mac[0] & 0xC0 != 0xC0:
            raise ValueError("static random BLE address должен начинаться с двух старших битов 11")
        flags |= FLAG_BLE_STATIC
        ble_addr_type = BLE_ADDR_STATIC
        ble_addr = static_mac

    # Identity keys are mandatory in a production record. Development boards
    # without a record continue to use the legacy SNV key path in firmware.
    irk = secrets.token_bytes(16)
    csrk = secrets.token_bytes(16)

    struct.pack_into("<IHHIHH", raw, 0, MAGIC, VERSION, RECORD_SIZE, serial, hw_revision, flags)
    raw[16:22] = ble_addr
    raw[22] = ble_addr_type
    raw[23] = 0xFF
    raw[24:40] = irk
    raw[40:56] = csrk
    struct.pack_into("<H", raw, 62, crc16_ccitt_false(raw[:62]))
    return bytes(raw)


def hex_line(address: int, record_type: int, data: bytes) -> str:
    body = bytes([len(data), (address >> 8) & 0xFF, address & 0xFF, record_type]) + data
    checksum = (-sum(body)) & 0xFF
    return ":" + (body + bytes([checksum])).hex().upper()


def to_intel_hex(address: int, payload: bytes) -> str:
    upper = (address >> 16) & 0xFFFF
    lines = [hex_line(0, 0x04, struct.pack(">H", upper))]
    base = address & 0xFFFF
    for offset in range(0, len(payload), 16):
        lines.append(hex_line(base + offset, 0x00, payload[offset : offset + 16]))
    lines.append(hex_line(0, 0x01, b""))
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Создать factory identity Test-DPLS")
    parser.add_argument("--serial", type=int, required=True, help="серийный номер 1..4294967294")
    parser.add_argument("--hw-revision", type=int, default=2, help="ревизия платы, по умолчанию 2")
    parser.add_argument("--output", type=Path, required=True, help="выходной Intel HEX")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--static-address", type=parse_mac, help="заданный BLE static random address")
    group.add_argument("--generate-static-address", action="store_true", help="сгенерировать BLE static random address")
    parser.add_argument("--metadata", type=Path, help="записать JSON без секретных ключей")
    args = parser.parse_args()

    static_mac = args.static_address
    if args.generate_static_address:
        static_mac = generate_static_mac()

    record = make_record(args.serial, args.hw_revision, static_mac)
    # Самопроверка тем же форматом, который проверяет firmware.
    if struct.unpack_from("<H", record, 62)[0] != crc16_ccitt_false(record[:62]):
        raise RuntimeError("внутренняя ошибка CRC factory identity")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(to_intel_hex(FLASH_ADDRESS, record), encoding="ascii")

    metadata = {
        "serial": args.serial,
        "hardware_revision": args.hw_revision,
        "flash_address": f"0x{FLASH_ADDRESS:08X}",
        "record_size": RECORD_SIZE,
        "ble_address_source": "static_random" if static_mac is not None else "phy6252_factory_public",
        "ble_address": static_mac.hex(":").upper() if static_mac is not None else None,
        "factory_keys": True,
        "record_sha256": hashlib.sha256(record).hexdigest(),
    }
    if args.metadata:
        args.metadata.parent.mkdir(parents=True, exist_ok=True)
        args.metadata.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(json.dumps(metadata, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
