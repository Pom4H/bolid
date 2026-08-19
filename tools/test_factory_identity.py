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
    gatt = Path("firmware/phy6252/dpls_gatt_service.c").read_text(encoding="utf-8")
    scatter = Path("firmware/targets/phy6252/scatter_load.sct").read_text(encoding="utf-8")
    gcc_ld = Path("firmware/targets/phy6252/phy6252.ld").read_text(encoding="utf-8")
    flash_factory = Path("tools/flash_factory_identity.sh").read_text(encoding="utf-8")
    mobile_ble = Path(
        "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsBle.kt"
    ).read_text(encoding="utf-8")
    credentials = Path(
        "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsCredentials.kt"
    ).read_text(encoding="utf-8")
    android_ble = Path(
        "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt"
    ).read_text(encoding="utf-8")
    ios_ble = Path(
        "mobile/core/src/iosMain/kotlin/ru/bolid/testdpls/core/app/IosBleTransport.kt"
    ).read_text(encoding="utf-8")
    advertisement = Path(
        "mobile/wire/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsAdvertisement.kt"
    ).read_text(encoding="utf-8")
    lab_ble = Path("tools/dpls-lab/native/DplsBle.swift").read_text(encoding="utf-8")

    # One production identity model only: a valid factory record is mandatory.
    assert "DPLS_LEGACY_BLE_MAC" not in identity
    assert "read_legacy_mac_snv" not in identity
    assert "development_id_from_mac" not in identity
    assert "LL_ENC_GenerateTrueRandNum" not in identity
    assert "read_key_snv" not in identity
    assert "write_key_snv" not in identity
    assert "factory_identity_load(&factory)" in identity
    assert "s_device_id = factory.serial_number" in identity

    assert "DPLS_FACTORY_IDENTITY_FLASH_ADDR" in identity
    assert "read_chip_factory_mac" in identity
    assert "HCI_EXT_SetBDADDRCmd" in identity
    assert "display_to_controller_addr" in identity
    assert "configure_static_identity_addr" in identity
    assert "dpls_ble_identity_is_ready" in peripheral
    assert "!link_up && !dpls_ble_identity_is_ready()" in peripheral

    # PHY6252 snapshots the peripheral local address during GAP_DeviceInit.
    # Static identity must therefore be configured in prepare(), before the
    # deferred SBP_START_DEVICE_EVT calls GAPRole_StartDevice().
    prepare_start = identity.index("void dpls_ble_identity_prepare(void)")
    stack_start = identity.index("void dpls_ble_identity_on_stack_started(void)")
    prepare_body = identity[prepare_start:stack_start]
    stack_body = identity[stack_start:]
    assert "configure_static_identity_addr(mac)" in prepare_body
    assert "GAP_ConfigDeviceAddr(ADDRTYPE_STATIC" not in stack_body
    init_call = peripheral.index("dpls_ble_identity_prepare();")
    start_event = peripheral.index("osal_set_event(app_task_id, SBP_START_DEVICE_EVT);")
    assert init_call < start_event

    # Provisioned records are complete identities, not just serial-number tags.
    assert "DPLS_FACTORY_FLAG_IRK | DPLS_FACTORY_FLAG_CSRK" in identity

    # The 4-hex air-name suffix is only a display hint. It must never become the
    # authoritative 32-bit NodeId before DEVICE_INFO_REPORT.
    assert "deviceId = null" in mobile_ble
    assert "authoritative deviceId" in mobile_ble

    # Current credential storage has one canonical production key plus a temporary
    # endpoint bootstrap key used only until DEVICE_INFO confirms the NodeId.
    assert 'add("endpoint:$it")' in credentials
    assert 'add("id:${nodeId.value}")' not in credentials
    assert 'add("addr:$it")' not in credentials
    assert 'add("legacy-addr:$it")' not in credentials

    # No Bluetooth SIG Company ID or old manufacturer payload anywhere in the current BLE path.
    assert "GAP_ADTYPE_MANUFACTURER_SPECIFIC" not in peripheral
    assert "0x01, 0x0b" not in peripheral.lower()
    assert "MANUFACTURER_ID" not in mobile_ble
    assert "manufacturerPayload" not in mobile_ble
    assert "getManufacturerSpecificData" not in android_ble
    assert "CBAdvertisementDataManufacturerDataKey" not in ios_ble
    assert "COMPANY_ID" not in advertisement
    assert "fun parse(raw:" not in advertisement
    assert "companyId" not in lab_ble
    assert "parseManufacturer" not in lab_ble
    assert "CBAdvertisementDataManufacturerDataKey" not in lab_ble

    # Pairing/security is expressed by GATT permissions, not by an advertising marker.
    assert (
        "clientCharCfgUUID}, GATT_PERMIT_READ | GATT_PERMIT_WRITE | "
        "GATT_PERMIT_ENCRYPT_WRITE"
    ) in gatt
    assert "status in CCCD_AUTH_STATUSES" in android_ble

    # Application XIP must stop before SNV. The three SNV sectors and the final
    # factory sector are persistent data, never linker spill space.
    assert "0x01C000" in scatter
    assert "0x1103C000" in scatter
    assert "0x1103F000" in scatter
    assert "LENGTH = 0x1c000" in gcc_ld
    assert "0x1103C000" in gcc_ld
    assert "0x1103F000" in gcc_ld

    # Factory data must be written as a raw 64-byte record at flash offset
    # 0x3F000. The generic `wh` path rewrites the application segment table.
    assert "FACTORY_OFFSET=0x3F000" in flash_factory
    assert "FACTORY_SIZE=64" in flash_factory
    assert ' -r we "$FACTORY_OFFSET" "$BIN"' in flash_factory
    assert " -r wh " not in flash_factory


def main() -> int:
    chip_record = factory.make_record(0x12345678, 2, None)
    assert len(chip_record) == factory.RECORD_SIZE
    assert factory.FLASH_OFFSET == 0x3F000
    assert struct.unpack_from("<I", chip_record, 0)[0] == factory.MAGIC
    assert struct.unpack_from("<H", chip_record, 4)[0] == factory.VERSION
    assert struct.unpack_from("<H", chip_record, 6)[0] == factory.RECORD_SIZE
    assert struct.unpack_from("<I", chip_record, 8)[0] == 0x12345678
    chip_flags = struct.unpack_from("<H", chip_record, 14)[0]
    assert chip_flags & factory.FLAG_IRK
    assert chip_flags & factory.FLAG_CSRK
    assert chip_record[16:22] == b"\xff" * 6
    assert chip_record[22] == factory.BLE_ADDR_CHIP_PUBLIC
    assert struct.unpack_from("<H", chip_record, 62)[0] == factory.crc16_ccitt_false(chip_record[:62])

    static_mac = bytes.fromhex("C23456789ABC")
    static_record = factory.make_record(42, 7, static_mac)
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
        factory.make_record(43, 2, bytes.fromhex("023456789ABC"))
    except ValueError:
        pass
    else:
        raise AssertionError("non-static BLE address was accepted")

    assert_source_contract()
    print("factory identity: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
