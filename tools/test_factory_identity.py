#!/usr/bin/env python3
"""Контракт BLE identity, зафиксированный по рабочей PB-03F release 1.4.0."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def main() -> int:
    identity = text("firmware/phy6252/dpls_ble_identity.c")
    header = text("firmware/phy6252/dpls_ble_identity.h")
    peripheral = text("firmware/targets/phy6252/source/dplsBLEPeripheral.c")
    scatter = text("firmware/targets/phy6252/scatter_load.sct")
    gcc_ld = text("firmware/targets/phy6252/phy6252.ld")
    flash = text("tools/flash_firmware.sh")
    build = text("tools/build_firmware.sh")
    gatt = text("firmware/phy6252/dpls_gatt_service.c")

    # В раннем boot запрещён отдельный raw factory sector. Эта схема появилась
    # после 1.4.0 и на реальной PB-03F дала no-start regression.
    for forbidden in (
        "DPLS_FACTORY_IDENTITY_FLASH_ADDR",
        "DPLS_FACTORY_IDENTITY_RECORD_SIZE",
        "factory_identity_load",
        "hal_flash_read",
        "dpls_ble_identity_is_ready",
        "dpls_ble_identity_is_provisioned",
        "0x1103F000",
    ):
        assert forbidden not in identity
        assert forbidden not in header

    # Identity использует проверенный vendor chip-MAC path, с SNV fallback.
    assert "check_chip_mAddr();" in identity
    assert "g_chipMAddr.chipMAddrStatus != CHIP_ID_VALID" in identity
    assert "HCI_EXT_SetBDADDRCmd(controller_addr)" in identity
    assert "DPLS_BLE_MAC_SNV_ID 0x82u" in identity
    assert "ensure_identity_keys" in identity

    # Controller BD_ADDR задаётся до deferred GAPRole_StartDevice().
    prepare = identity[identity.index("void dpls_ble_identity_prepare(void)"):]
    assert "ensure_mac(mac)" in prepare
    assert "set_controller_public_addr(mac)" in identity
    assert peripheral.index("dpls_ble_identity_prepare();") < peripheral.index(
        "osal_set_event(app_task_id, SBP_START_DEVICE_EVT);"
    )

    # Advertising не имеет identity-ready gate. Если identity не подготовилась,
    # живая плата всё равно должна быть видна как Test-DPLS-0000.
    assert "dpls_ble_identity_is_ready" not in peripheral
    assert "if (dpls_phy6252_flash_work_pending()) return false;" in peripheral
    assert '"Test-DPLS-0000"' in peripheral

    # Возвращён аппаратно проверенный 1.4.0 linker window. SNV по-прежнему
    # монтируется в 0x1103C000, но linker region больше не перекраивает boot image.
    assert "LR_ROM_XIP 0x11020000 0x020000" in scatter
    assert "ER_ROM_XIP 0x11020000 0x020000" in scatter
    assert "LENGTH = 0x20000" in gcc_ld
    assert "hal_fs_init(0x1103C000u, 3)" in peripheral

    # Сборка и прошивка имеют один artifact и одну programmer operation.
    for forbidden in (
        "--serial",
        "--factory-bin",
        "factory.bin",
        "identity.json",
        "make_factory_identity",
        "0x3F000",
    ):
        assert forbidden not in build
        assert forbidden not in flash
    assert 'wh "$HEX"' in flash
    assert " we " not in flash
    for removed in (
        "tools/make_factory_identity.py",
        "tools/flash_factory_identity.sh",
        "tools/provision_test_dpls.sh",
    ):
        assert not (ROOT / removed).exists(), removed

    # Проверенные platform-инварианты стартового пути не трогаем.
    assert "hal_pwrmgr_RAM_retention(RET_SRAM0 | RET_SRAM1 | RET_SRAM2)" in peripheral
    assert "hal_pwrmgr_RAM_retention_set()" in peripheral
    assert "hal_pwrmgr_LowCurrentLdo_enable()" in peripheral
    assert "hal_pwrmgr_register(MOD_USR1, NULL, NULL)" in peripheral

    # Security boundary остаётся текущей: CCCD до SMP, protocol RX encrypted.
    assert "dpls_rx_uuid}, GATT_PERMIT_WRITE | GATT_PERMIT_ENCRYPT_WRITE" in gatt
    assert "clientCharCfgUUID}, GATT_PERMIT_READ | GATT_PERMIT_WRITE," in gatt

    print("hardware-safe BLE identity / boot contract: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
