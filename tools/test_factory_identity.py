#!/usr/bin/env python3
"""Offline contract test for production Test-DPLS identity."""

import struct
from pathlib import Path

import make_factory_identity as factory


def assert_hex_checksums(text: str) -> None:
    for line in text.strip().splitlines():
        assert line.startswith(":")
        raw = bytes.fromhex(line[1:])
        assert sum(raw) & 0xFF == 0


def assert_source_contract() -> None:
    identity = Path("firmware/phy6252/dpls_ble_identity.c").read_text(encoding="utf-8")
    peripheral = Path("firmware/targets/phy6252/source/dplsBLEPeripheral.c").read_text(encoding="utf-8")
    scatter = Path("firmware/targets/phy6252/scatter_load.sct").read_text(encoding="utf-8")
    gcc_ld = Path("firmware/targets/phy6252/phy6252.ld").read_text(encoding="utf-8")

    # Production firmware may consume randomness for keys, but must never mint
    # a new BLE address at boot or persist one into the legacy 0x82 slot.
    assert "generate_mac" not in identity
    assert "write_mac_snv" not in identity
    assert "DPLS_FACTORY_IDENTITY_FLASH_ADDR" in identity
    assert "read_chip_factory_mac" in identity
    assert "dpls_ble_identity_is_ready" in peripheral

    # A foreign/unallocated Company ID must not creep back into new firmware.
    assert "GAP_ADTYPE_MANUFACTURER_SPECIFIC" not in peripheral
    assert "0x01, 0x0b" not in peripheral.lower()

    # Both production linkers must leave the final 4 KiB sector untouched.
    assert "0x01F000" in scatter
    assert "0x1103F000" in scatter
    assert "LENGTH = 0x1f000" in gcc_ld
    assert "0x1103F000" in gcc_ld


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

    generated_mac = factory.generate_static_mac()
    assert len(generated_mac) == 6
    assert generated_mac[0] & 0xC0 == 0xC0

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

    assert_source_contract()
    print("factory identity: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
