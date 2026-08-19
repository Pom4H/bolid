#!/usr/bin/env python3
"""Offline contract test for tools/make_factory_identity.py."""

import struct

import make_factory_identity as factory


def assert_hex_checksums(text: str) -> None:
    for line in text.strip().splitlines():
        assert line.startswith(":")
        raw = bytes.fromhex(line[1:])
        assert sum(raw) & 0xFF == 0


def main() -> int:
    chip_record = factory.make_record(0x12345678, 2, None, True)
    assert len(chip_record) == factory.RECORD_SIZE
    assert struct.unpack_from("<I", chip_record, 0)[0] == factory.MAGIC
    assert struct.unpack_from("<H", chip_record, 4)[0] == factory.VERSION
    assert struct.unpack_from("<H", chip_record, 6)[0] == factory.RECORD_SIZE
    assert struct.unpack_from("<I", chip_record, 8)[0] == 0x12345678
    assert chip_record[16:22] == b"\xff" * 6
    assert chip_record[22] == factory.BLE_ADDR_CHIP_PUBLIC
    assert struct.unpack_from("<H", chip_record, 62)[0] == factory.crc16_ccitt_false(chip_record[:62])

    static_mac = bytes.fromhex("C23456789ABC")
    static_record = factory.make_record(42, 7, static_mac, True)
    flags = struct.unpack_from("<H", static_record, 14)[0]
    assert flags & factory.FLAG_BLE_STATIC
    assert flags & factory.FLAG_IRK
    assert flags & factory.FLAG_CSRK
    assert static_record[16:22] == static_mac
    assert static_record[22] == factory.BLE_ADDR_STATIC
    assert struct.unpack_from("<H", static_record, 62)[0] == factory.crc16_ccitt_false(static_record[:62])

    ihex = factory.to_intel_hex(factory.FLASH_ADDRESS, static_record)
    assert ihex.splitlines()[0] == ":020000041103E6"
    assert ihex.splitlines()[-1] == ":00000001FF"
    assert_hex_checksums(ihex)

    try:
        factory.make_record(43, 2, bytes.fromhex("023456789ABC"), True)
    except ValueError:
        pass
    else:
        raise AssertionError("non-static BLE address was accepted")

    print("factory identity: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
