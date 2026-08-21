#!/usr/bin/env python3
"""Offline contract for strict runtime identity and build-time personalization."""

import struct
from pathlib import Path

import make_factory_identity as factory

ROOT = Path(__file__).resolve().parents[1]


def assert_hex_checksums(text: str) -> None:
    for line in text.strip().splitlines():
        assert line.startswith(":")
        raw = bytes.fromhex(line[1:])
        assert len(raw) == raw[0] + 5
        assert sum(raw) & 0xFF == 0


def assert_source_contract() -> None:
    identity = (ROOT / "firmware/phy6252/dpls_ble_identity.c").read_text(encoding="utf-8")
    peripheral = (ROOT / "firmware/targets/phy6252/source/dplsBLEPeripheral.c").read_text(encoding="utf-8")
    gatt = (ROOT / "firmware/phy6252/dpls_gatt_service.c").read_text(encoding="utf-8")
    scatter = (ROOT / "firmware/targets/phy6252/scatter_load.sct").read_text(encoding="utf-8")
    gcc_ld = (ROOT / "firmware/targets/phy6252/phy6252.ld").read_text(encoding="utf-8")
    flash_factory = (ROOT / "tools/flash_factory_identity.sh").read_text(encoding="utf-8")
    build = (ROOT / "tools/build_firmware.sh").read_text(encoding="utf-8")
    maker = (ROOT / "tools/make_factory_identity.py").read_text(encoding="utf-8")
    mobile_ble = (ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsBle.kt").read_text(encoding="utf-8")
    credentials = (ROOT / "mobile/core/src/commonMain/kotlin/ru/bolid/testdpls/core/app/DplsCredentials.kt").read_text(encoding="utf-8")
    android_ble = (ROOT / "mobile/core/src/androidMain/kotlin/ru/bolid/testdpls/core/app/AndroidBleTransport.kt").read_text(encoding="utf-8")
    ios_ble = (ROOT / "mobile/core/src/iosMain/kotlin/ru/bolid/testdpls/core/app/IosBleTransport.kt").read_text(encoding="utf-8")
    advertisement = (ROOT / "mobile/wire/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsAdvertisement.kt").read_text(encoding="utf-8")
    lab_ble = (ROOT / "tools/dpls-lab/native/DplsBle.swift").read_text(encoding="utf-8")

    # Runtime has one identity truth only: the factory record. Migration,
    # random-key generation and legacy SNV recovery belong to tooling, not MCU code.
    for forbidden in (
        "DPLS_LEGACY_BLE_MAC",
        "read_legacy_mac_snv",
        "legacy_device_id_from_mac",
        "LL_ENC_GenerateTrueRandNum",
        "read_key_snv",
        "write_key_snv",
    ):
        assert forbidden not in identity
    assert "factory_identity_load(&factory)" in identity
    assert "if (!factory_identity_load(&factory)) return;" in identity
    assert "s_device_id = factory.serial_number" in identity
    assert "s_factory_provisioned = true" in identity

    # Build/provision boundary: personalization can be created once from a serial
    # or reproduced from the saved factory BIN, and the resulting HEX contains
    # application + factory sector in one programmer-safe image.
    assert "--serial" in build
    assert "--factory-bin" in build
    assert "--merge-app-hex" in build
    assert "--flash-ready-output" in build
    assert "identity: embedded factory sector" in build
    assert "identity: application-only" in build
    assert "merge_factory_into_hex" in maker
    assert "record_type == 0x05" in maker

    assert "DPLS_FACTORY_IDENTITY_FLASH_ADDR" in identity
    assert "read_chip_factory_mac" in identity
    assert "HCI_EXT_SetBDADDRCmd" in identity
    assert "display_to_controller_addr" in identity
    assert "configure_static_identity_addr" in identity
    assert "dpls_ble_identity_is_ready" in peripheral
    assert "!link_up && !dpls_ble_identity_is_ready()" in peripheral

    # Hardware-only boot invariants recovered during the SDK 3.1.2 migration.
    assert "hal_pwrmgr_RAM_retention(RET_SRAM0 | RET_SRAM1 | RET_SRAM2)" in peripheral
    assert "hal_pwrmgr_RAM_retention_set()" in peripheral
    assert "hal_fs_init(0x1103C000u, 3)" in peripheral

    # Static identity is configured before GAPRole_StartDevice().
    prepare_start = identity.index("void dpls_ble_identity_prepare(void)")
    stack_start = identity.index("void dpls_ble_identity_on_stack_started(void)")
    prepare_body = identity[prepare_start:stack_start]
    stack_body = identity[stack_start:]
    assert "configure_static_identity_addr(mac)" in prepare_body
    assert "GAP_ConfigDeviceAddr(ADDRTYPE_STATIC" not in stack_body
    init_call = peripheral.index("dpls_ble_identity_prepare();")
    start_event = peripheral.index("osal_set_event(app_task_id, SBP_START_DEVICE_EVT);")
    assert init_call < start_event

    # The 4-hex air-name suffix is only a display hint; DEVICE_INFO owns NodeId.
    assert "deviceId = null" in mobile_ble
    assert "authoritative deviceId" in mobile_ble
    assert 'add("endpoint:$it")' in credentials
    assert 'add("id:${nodeId.value}")' not in credentials
    assert 'add("addr:$it")' not in credentials

    # No old manufacturer payload remains in the current BLE path.
    assert "GAP_ADTYPE_MANUFACTURER_SPECIFIC" not in peripheral
    assert "MANUFACTURER_ID" not in mobile_ble
    assert "manufacturerPayload" not in mobile_ble
    assert "getManufacturerSpecificData" not in android_ble
    assert "CBAdvertisementDataManufacturerDataKey" not in ios_ble
    assert "COMPANY_ID" not in advertisement
    assert "parseManufacturer" not in lab_ble

    # Pairing starts on encrypted protocol RX; CCCD remains writable before SMP.
    assert "dpls_rx_uuid}, GATT_PERMIT_WRITE | GATT_PERMIT_ENCRYPT_WRITE" in gatt
    assert "clientCharCfgUUID}, GATT_PERMIT_READ | GATT_PERMIT_WRITE," in gatt
    assert (
        "clientCharCfgUUID}, GATT_PERMIT_READ | GATT_PERMIT_WRITE | GATT_PERMIT_ENCRYPT_WRITE"
        not in gatt
    )

    # Application XIP cannot spill into SNV/factory sectors.
    assert "0x01C000" in scatter
    assert "0x1103C000" in scatter
    assert "0x1103F000" in scatter
    assert "LENGTH = 0x1c000" in gcc_ld
    assert "0x1103C000" in gcc_ld
    assert "0x1103F000" in gcc_ld

    # Standalone factory flashing still writes the raw 64-byte sector, never the
    # generic application-header path.
    assert "FACTORY_OFFSET=0x3F000" in flash_factory
    assert "FACTORY_SIZE=64" in flash_factory
    assert ' -r we "$FACTORY_OFFSET" "$BIN"' in flash_factory
    assert " -r wh " not in flash_factory


def main() -> int:
    chip_record = factory.make_record(0x12345678, 2, None)
    info = factory.validate_record(chip_record)
    assert len(chip_record) == factory.RECORD_SIZE
    assert info["serial"] == 0x12345678
    assert info["hardware_revision"] == 2
    assert factory.FLASH_OFFSET == 0x3F000
    assert struct.unpack_from("<H", chip_record, 62)[0] == factory.crc16_ccitt_false(chip_record[:62])

    static_mac = bytes.fromhex("C23456789ABC")
    static_record = factory.make_record(42, 7, static_mac)
    info = factory.validate_record(static_record)
    assert info["static_mac"] == static_mac

    generated_mac = factory.generate_static_mac()
    assert len(generated_mac) == 6
    assert generated_mac[0] & 0xC0 == 0xC0

    identity_hex = factory.to_intel_hex(factory.FLASH_ADDRESS, static_record)
    assert identity_hex.splitlines()[0] == ":020000041103E6"
    assert identity_hex.splitlines()[-1] == ":00000001FF"
    assert_hex_checksums(identity_hex)

    # Simulate a compiler HEX containing a start-linear-address (type 05). The
    # bundled programmer stops parsing at type 05, so the composer must remove
    # it before appending the factory sector.
    app_hex = "\n".join(
        [
            factory.hex_line(0, 0x04, bytes.fromhex("1100")),
            factory.hex_line(0x0000, 0x00, b"\x01\x02\x03\x04"),
            factory.hex_line(0, 0x05, struct.pack(">I", 0x1FFF1838)),
            ":00000001FF",
        ]
    ) + "\n"
    merged = factory.merge_factory_into_hex(app_hex, static_record)
    assert_hex_checksums(merged)
    assert not any(line[7:9] == "05" for line in merged.splitlines())
    assert ":020000041103E6" in merged
    assert merged.splitlines()[-1] == ":00000001FF"

    overlap_hex = "\n".join(
        [
            factory.hex_line(0, 0x04, bytes.fromhex("1103")),
            factory.hex_line(0xF000, 0x00, b"\x00"),
            ":00000001FF",
        ]
    ) + "\n"
    try:
        factory.merge_factory_into_hex(overlap_hex, static_record)
    except ValueError as exc:
        assert "пересекает factory identity sector" in str(exc)
    else:
        raise AssertionError("factory-sector overlap was accepted")

    try:
        factory.make_record(43, 2, bytes.fromhex("023456789ABC"))
    except ValueError:
        pass
    else:
        raise AssertionError("non-static BLE address was accepted")

    assert_source_contract()
    print("factory identity / flash-ready build boundary: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
