#!/usr/bin/env python3
"""Create and package Test-DPLS factory identity.

The runtime firmware has exactly one identity model: a CRC-protected factory
record at 0x1103F000. This tool owns personalization and can compose that record
with an application Intel HEX into one flash-ready image.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import struct
from pathlib import Path

FLASH_ADDRESS = 0x1103F000
FLASH_OFFSET = 0x0003F000
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


def validate_record(record: bytes) -> dict[str, object]:
    if len(record) != RECORD_SIZE:
        raise ValueError(f"factory record должен быть ровно {RECORD_SIZE} байт")
    magic, version, length, serial, hw_revision, flags = struct.unpack_from("<IHHIHH", record, 0)
    if magic != MAGIC or version != VERSION or length != RECORD_SIZE:
        raise ValueError("factory record имеет неверный magic/version/length")
    if not 1 <= serial <= 0xFFFFFFFE:
        raise ValueError("factory record содержит недопустимый serial")
    if flags & ~(FLAG_BLE_STATIC | FLAG_IRK | FLAG_CSRK):
        raise ValueError("factory record содержит неизвестные flags")
    if flags & (FLAG_IRK | FLAG_CSRK) != (FLAG_IRK | FLAG_CSRK):
        raise ValueError("factory record не содержит обязательные IRK/CSRK")
    if struct.unpack_from("<H", record, 62)[0] != crc16_ccitt_false(record[:62]):
        raise ValueError("factory record CRC не совпадает")
    if record[24:40] in (b"\x00" * 16, b"\xff" * 16):
        raise ValueError("factory IRK недопустим")
    if record[40:56] in (b"\x00" * 16, b"\xff" * 16):
        raise ValueError("factory CSRK недопустим")

    static = bool(flags & FLAG_BLE_STATIC)
    address = bytes(record[16:22])
    if static:
        if record[22] != BLE_ADDR_STATIC or address[0] & 0xC0 != 0xC0:
            raise ValueError("factory static BLE address недопустим")
    elif record[22] != BLE_ADDR_CHIP_PUBLIC:
        raise ValueError("factory BLE address type недопустим")

    return {
        "serial": serial,
        "hardware_revision": hw_revision,
        "flags": flags,
        "static_mac": address if static else None,
    }


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


def decode_hex_line(line: str) -> tuple[int, int, bytes]:
    if not line.startswith(":"):
        raise ValueError("Intel HEX: строка не начинается с ':'")
    try:
        raw = bytes.fromhex(line[1:])
    except ValueError as exc:
        raise ValueError("Intel HEX: невалидный hex") from exc
    if len(raw) < 5 or len(raw) != raw[0] + 5:
        raise ValueError("Intel HEX: неверная длина записи")
    if sum(raw) & 0xFF:
        raise ValueError("Intel HEX: неверная checksum")
    address = (raw[1] << 8) | raw[2]
    return address, raw[3], raw[4:-1]


def merge_factory_into_hex(app_hex: str, record: bytes) -> str:
    """Return one programmer-safe application + factory Intel HEX.

    rdwr_phy62x2.py stops parsing at record type 05, so start-linear-address
    records are intentionally discarded. They are irrelevant to programming.
    Only type 00/04 application records are accepted; this keeps the generated
    image deterministic and prevents an accidental overlap with factory data.
    """
    validate_record(record)
    output: list[str] = []
    upper = 0
    saw_eof = False
    factory_end = FLASH_ADDRESS + RECORD_SIZE

    for original in app_hex.splitlines():
        line = original.strip()
        if not line:
            continue
        address, record_type, data = decode_hex_line(line)
        if record_type == 0x04:
            if len(data) != 2:
                raise ValueError("Intel HEX: type 04 должен содержать 2 байта")
            upper = int.from_bytes(data, "big") << 16
            output.append(line.upper())
        elif record_type == 0x00:
            absolute = upper + address
            if absolute < factory_end and absolute + len(data) > FLASH_ADDRESS:
                raise ValueError("application HEX пересекает factory identity sector")
            output.append(line.upper())
        elif record_type == 0x01:
            saw_eof = True
        elif record_type == 0x05:
            # The bundled PHY62x2 programmer treats type 05 as end-of-image.
            # Drop it so the appended factory sector is actually programmed.
            continue
        else:
            raise ValueError(f"Intel HEX: неподдерживаемый record type 0x{record_type:02X}")

    if not saw_eof:
        raise ValueError("application HEX не содержит EOF record")

    factory_lines = to_intel_hex(FLASH_ADDRESS, record).splitlines()
    output.extend(factory_lines[:-1])
    output.append(":00000001FF")
    return "\n".join(output) + "\n"


def write_secret_binary(path: Path, record: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(record)
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def main() -> int:
    parser = argparse.ArgumentParser(description="Создать/упаковать factory identity Test-DPLS")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--serial", type=int, help="создать новую identity для serial 1..4294967294")
    source.add_argument("--record-input", type=Path, help="переиспользовать существующий 64-байтный factory BIN")
    parser.add_argument("--hw-revision", type=int, default=2, help="ревизия платы, по умолчанию 2")
    parser.add_argument("--binary-output", type=Path, required=True, help="64-байтный factory BIN")
    parser.add_argument("--hex-output", type=Path, help="отдельный Intel HEX factory sector")
    parser.add_argument("--merge-app-hex", type=Path, help="application HEX для создания flash-ready образа")
    parser.add_argument("--flash-ready-output", type=Path, help="application + factory Intel HEX")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--static-address", type=parse_mac, help="заданный BLE static random address")
    group.add_argument("--generate-static-address", action="store_true", help="сгенерировать BLE static random address")
    parser.add_argument("--metadata", type=Path, help="записать JSON без секретных ключей")
    args = parser.parse_args()

    if (args.merge_app_hex is None) != (args.flash_ready_output is None):
        parser.error("--merge-app-hex и --flash-ready-output задаются только вместе")
    if args.record_input and (args.static_address is not None or args.generate_static_address):
        parser.error("BLE address options нельзя применять к готовому --record-input")

    if args.record_input:
        record = args.record_input.read_bytes()
        info = validate_record(record)
    else:
        static_mac = args.static_address
        if args.generate_static_address:
            static_mac = generate_static_mac()
        record = make_record(args.serial, args.hw_revision, static_mac)
        info = validate_record(record)

    write_secret_binary(args.binary_output, record)
    if args.hex_output:
        args.hex_output.parent.mkdir(parents=True, exist_ok=True)
        args.hex_output.write_text(to_intel_hex(FLASH_ADDRESS, record), encoding="ascii")
    if args.flash_ready_output:
        args.flash_ready_output.parent.mkdir(parents=True, exist_ok=True)
        merged = merge_factory_into_hex(args.merge_app_hex.read_text(encoding="ascii"), record)
        args.flash_ready_output.write_text(merged, encoding="ascii")

    static_mac = info["static_mac"]
    metadata = {
        "serial": info["serial"],
        "hardware_revision": info["hardware_revision"],
        "flash_address": f"0x{FLASH_ADDRESS:08X}",
        "flash_offset": f"0x{FLASH_OFFSET:08X}",
        "record_size": RECORD_SIZE,
        "ble_address_source": "static_random" if static_mac is not None else "phy6252_factory_public",
        "ble_address": static_mac.hex(":").upper() if isinstance(static_mac, bytes) else None,
        "factory_keys": True,
        "record_sha256": hashlib.sha256(record).hexdigest(),
        "flash_ready_hex": str(args.flash_ready_output) if args.flash_ready_output else None,
    }
    if args.metadata:
        args.metadata.parent.mkdir(parents=True, exist_ok=True)
        args.metadata.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(json.dumps(metadata, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
