#!/usr/bin/env python3
"""Fail the build when the PHY6252 electrical/power contract drifts."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BOARD = ROOT / "Firmware/phy6252/dpls_board.h"
HW = ROOT / "Firmware/phy6252/dpls_phy6252_hw.c"
ADC = ROOT / "Firmware/phy6252/dpls_phy6252_adc.c"
APP = ROOT / "Firmware/phy6252/dpls_phy6252_app.c"
TARGET = ROOT / "Firmware/targets/phy6252/source/dplsBLEPeripheral.c"
MAIN = ROOT / "Firmware/targets/phy6252/source/dpls_main.c"
CPROJECT = ROOT / "Firmware/targets/phy6252/test-dpls.cproject.yml"
SCATTER = ROOT / "Firmware/targets/phy6252/scatter_load.sct"
CHECKLIST = ROOT / "docs/bring-up-checklist.md"
SDK_PATCH = ROOT / "tools/patch_phy6252_sdk.py"

DEFINE_RE = re.compile(r"^\s*#define\s+(DPLS_PIN_[A-Z0-9_]+)\s+([^/\r\n]+)", re.MULTILINE)

EXPECTED = {
    "DPLS_PIN_ISO_1": "P31",
    "DPLS_PIN_ISO_2": "P32",
    "DPLS_PIN_ISO_T": "P33",
    "DPLS_PIN_KZ_1": "P14",
    "DPLS_PIN_KZ_2": "P16",
    "DPLS_PIN_KZ_T": "P17",
    "DPLS_PIN_PORT1_ADC": "P20",
    "DPLS_PIN_PORT2_ADC": "P15",
    "DPLS_PIN_PORT_T_ADC": "P24",
    "DPLS_PIN_VCAP_ADC": "P23",
    "DPLS_PIN_LED_RED": "P07",
    "DPLS_PIN_LED_GREEN": "P11",
    "DPLS_PIN_LED_BLUE": "P18",
    "DPLS_PIN_FACTORY_RESET": "P34",
}

EXPECTED_MODE_OUTPUT = {
    "DPLS_MODE_OPEN_T": "DPLS_PIN_ISO_T",
    "DPLS_MODE_OPEN_MAIN": "DPLS_PIN_ISO_2",
    "DPLS_MODE_SHORT_1": "DPLS_PIN_KZ_1",
    "DPLS_MODE_SHORT_2": "DPLS_PIN_KZ_2",
    "DPLS_MODE_SHORT_T": "DPLS_PIN_KZ_T",
}


def fail(message: str) -> None:
    raise SystemExit(f"PHY6252 hardware contract: {message}")


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        fail(f"cannot read {path.relative_to(ROOT)}: {exc}")


def require(text: str, needle: str, where: Path) -> None:
    if needle not in text:
        fail(f"{where.relative_to(ROOT)} must contain {needle!r}")


def forbid(text: str, needle: str, where: Path) -> None:
    if needle in text:
        fail(f"{where.relative_to(ROOT)} must not contain {needle!r}")


def resolve_pin(name: str, defs: dict[str, str], seen: set[str] | None = None) -> str:
    seen = set() if seen is None else set(seen)
    if name in seen:
        fail(f"cyclic pin alias at {name}")
    seen.add(name)
    raw = defs.get(name)
    if raw is None:
        fail(f"missing {name} in dpls_board.h")
    token = raw.strip().split()[0].strip("()")
    match = re.fullmatch(r"GPIO_P(\d{2})", token)
    if match:
        return f"P{match.group(1)}"
    if token.startswith("DPLS_PIN_"):
        return resolve_pin(token, defs, seen)
    fail(f"unsupported pin expression {name}={raw!r}")
    raise AssertionError("unreachable")


def mode_block(hw: str, mode: str) -> str:
    match = re.search(
        rf"case\s+{re.escape(mode)}\s*:(.*?)(?=\n\s*case\s+DPLS_MODE_|\n\s*default\s*:)",
        hw,
        re.DOTALL,
    )
    if not match:
        fail(f"mode switch block {mode} missing")
    return match.group(1)


def function_block(text: str, name: str) -> str:
    match = re.search(
        rf"\b{name}\s*\([^)]*\)\s*\{{(.*?)\n\}}",
        text,
        re.DOTALL,
    )
    if not match:
        fail(f"function {name} missing")
    return match.group(1)


def main() -> None:
    board = read(BOARD)
    hw = read(HW)
    adc = read(ADC)
    app = read(APP)
    target = read(TARGET)
    startup = read(MAIN)
    cproject = read(CPROJECT)
    scatter = read(SCATTER)
    checklist = read(CHECKLIST)
    sdk_patch = read(SDK_PATCH)

    defs = {name: value.strip() for name, value in DEFINE_RE.findall(board)}
    resolved = {name: resolve_pin(name, defs) for name in EXPECTED}
    if resolved != EXPECTED:
        differences = [
            f"{name}: expected {EXPECTED[name]}, got {resolved.get(name, '<missing>')}"
            for name in EXPECTED
            if resolved.get(name) != EXPECTED[name]
        ]
        fail("pin map changed:\n  " + "\n  ".join(differences))

    by_pin: dict[str, list[str]] = {}
    for role, pin in resolved.items():
        by_pin.setdefault(pin, []).append(role)
    collisions = {pin: roles for pin, roles in by_pin.items() if len(roles) != 1}
    if collisions:
        fail(f"physical pin overlap: {collisions}")
    if resolve_pin("DPLS_PIN_LINE_ADC", defs) != EXPECTED["DPLS_PIN_PORT1_ADC"]:
        fail("DPLS_PIN_LINE_ADC must remain an alias of DPLS_PIN_PORT1_ADC")

    # Digital hardware has one owner. MOD_USR1 is a safety guard for asserted
    # outputs; ordinary BLE/ADC coexistence is handled by the radio-event gate.
    require(hw, "hal_pwrmgr_register(MOD_USR1, NULL, disable_32k_xtal)", HW)
    require(hw, "control_sleep_guard_acquire", HW)
    require(hw, "hal_pwrmgr_lock(MOD_USR1)", HW)
    require(hw, "hal_pwrmgr_unlock(MOD_USR1)", HW)
    require(hw, "prime_all_outputs_low", HW)
    require(hw, "register_output_retention", HW)
    require(hw, "subWriteReg(&(AP_AON->PMCTL0), 28, 28, 0x00)", HW)
    forbid(hw, "hal_pwrmgr_register(MOD_USR2", HW)
    connection_start = function_block(hw, "dpls_phy6252_hw_connection_lock")
    if "hal_pwrmgr_lock(" in connection_start:
        fail("normal BLE connection must not hold MOD_USR1 for its whole lifetime")
    for symbol in (
        "DPLS_PIN_ISO_1", "DPLS_PIN_ISO_2", "DPLS_PIN_ISO_T",
        "DPLS_PIN_KZ_1", "DPLS_PIN_KZ_2", "DPLS_PIN_KZ_T",
        "DPLS_PIN_LED_RED", "DPLS_PIN_LED_GREEN", "DPLS_PIN_LED_BLUE",
    ):
        require(hw, symbol, HW)

    write_re = re.compile(r"hal_gpio_write\((DPLS_PIN_[A-Z0-9_]+),\s*1\s*\)")
    for mode, expected_pin in EXPECTED_MODE_OUTPUT.items():
        writes = write_re.findall(mode_block(hw, mode))
        if writes != [expected_pin]:
            fail(f"{mode}: expected active output {expected_pin}, got {writes}")

    # ADC mux/callback pairing, lost-IRQ recovery, stale invalidation and a real
    # MOD_ADCC registration probe. Vendor hal_adc_init() is void and otherwise
    # hides pwrmgr registration failure.
    for needle in (
        "ADC_BIT(ADC_CH3P_P20)", "result_channel = ADC_CH9",
        "ADC_BIT(ADC_CH3N_P15)", "result_channel = ADC_CH4",
        "ADC_BIT(ADC_CH2N_P24)", "result_channel = ADC_CH2",
        "ADC_BIT(ADC_CH1P_P23)", "result_channel = ADC_CH1",
        "DPLS_ADC_NEED_ALL", "DPLS_ADC_STALE_MS", "DPLS_ADC_CONVERSION_TIMEOUT_MS",
        "dpls_adc_assert_port1", "dpls_adc_assert_port2",
        "dpls_adc_assert_port_t", "dpls_adc_assert_vcap",
        "hal_adc_stop();", "finish_inflight_as_failed();",
        "hal_pwrmgr_lock(MOD_ADCC)", "hal_pwrmgr_unlock(MOD_ADCC)",
        "dpls_phy6252_adc_set_radio_gated", "dpls_phy6252_adc_after_radio_event",
        "if (!radio_gated)",
    ):
        require(adc, needle, ADC)
    forbid(adc, "ADC_CH1N_P11", ADC)

    # Application consumes services rather than reopening low-level drivers.
    for needle in (
        "hal.port1_voltage_mv = port1_voltage_mv",
        "hal.port2_voltage_mv = port2_voltage_mv",
        "hal.port_t_voltage_mv = port_t_voltage_mv",
        "hal.reserve_voltage_mv = reserve_voltage_mv",
        "dpls_phy6252_hw_connection_lock()",
        "dpls_phy6252_adc_tick(now)",
        "hardware_ok = dpls_phy6252_hw_ready()",
    ):
        require(app, needle, APP)
    for forbidden in (
        "hal_pwrmgr_register(", "hal_pwrmgr_lock(", "hal_adc_config_channel(",
        "hal_adc_start(", "hal_gpio_pin_init(DPLS_PIN_ISO", "hal_gpio_pin_init(DPLS_PIN_KZ",
    ):
        forbid(app, forbidden, APP)

    # Factory reset must disconnect first because the pinned GAPBondMgr defers
    # ERASE_ALLBONDS while a link is active. Reboot is only allowed after bond
    # count, identity keys and EMPTY settings were all verified.
    for needle in (
        "factory_reset_pending",
        "finish_factory_reset",
        "GAPBOND_ERASE_ALLBONDS",
        "GAPBOND_BOND_COUNT",
        "DPLS_BOND_RECORDS_PER_SLOT 6u",
        "index * DPLS_BOND_RECORDS_PER_SLOT + offset",
        "bytes[i] != 0xffu",
        "bond_records_erased()",
        "dpls_ble_identity_reset_bonding_keys()",
        "clear_settings_for_factory_reset",
    ):
        require(app, needle, APP)

    # BLE target is integration glue and owns the early one-shot hardware init.
    require(target, "dpls_phy6252_hw_init()", TARGET)
    # The board has a stable public address. Its pinned SMP stack must not send
    # a peripheral IRK: Samsung Android treats that legacy identity payload as
    # an invalid SIRK and rejects the otherwise encrypted bond.
    require(target, "GAPBOND_KEYDIST_SENCKEY", TARGET)
    require(target, "GAPBOND_KEYDIST_MENCKEY", TARGET)
    require(target, "GAPBOND_KEYDIST_MIDKEY", TARGET)
    forbid(target, "GAPBOND_KEYDIST_SIDKEY", TARGET)
    # These are the v1.1.0 parameters validated on Samsung Android. Enabling
    # slave latency caused indications to race characteristic-write completion
    # and left the Android GATT queue permanently busy (status 201).
    require(target, "#define DEFAULT_MIN_CONN_INTERVAL 24", TARGET)
    require(target, "#define DEFAULT_MAX_CONN_INTERVAL 80", TARGET)
    require(target, "#define DEFAULT_SLAVE_LATENCY 0", TARGET)
    require(target, "#define DPLS_TICK_MS 200u", TARGET)
    require(target, "LL_EXT_ConnEventNotice(app_task_id, SBP_DPLS_CONN_EVT)", TARGET)
    require(target, "dpls_phy6252_adc_set_full_scan(true)", TARGET)
    require(target, "dpls_phy6252_adc_set_radio_gated(true)", TARGET)
    require(target, "dpls_phy6252_adc_after_radio_event()", TARGET)
    require(sdk_patch, "peer-address slave ID key override", SDK_PATCH)
    require(sdk_patch, "Respect GAPBOND_KEY_DIST_LIST", SDK_PATCH)
    for forbidden in (
        "hal_pwrmgr_register(MOD_USR1", "hal_pwrmgr_register(MOD_USR2",
        "disable_32k_xtal", "prime_safe_gpio_outputs",
    ):
        forbid(target, forbidden, TARGET)

    # Production power contract: only SRAM0 is retained, and the chip's
    # low-current retention LDO path is compiled in. The MAP gate checks the
    # runtime consequence after every AC6 link.
    require(startup, "hal_pwrmgr_RAM_retention(RET_SRAM0)", MAIN)
    require(startup, "hal_pwrmgr_LowCurrentLdo_enable()", MAIN)
    forbid(startup, "RET_SRAM1", MAIN)
    forbid(startup, "RET_SRAM2", MAIN)
    require(target, "hal_pwrmgr_RAM_retention(RET_SRAM0)", TARGET)
    forbid(target, "RET_SRAM1", TARGET)
    forbid(target, "RET_SRAM2", TARGET)
    require(cproject, "CFG_SRAM_RETENTION_LOW_CURRENT_LDO_ENABLE", CPROJECT)

    require(cproject, "../../phy6252/dpls_phy6252_hw.c", CPROJECT)
    require(cproject, "../../phy6252/dpls_phy6252_adc.c", CPROJECT)

    require(scatter, "dpls_phy6252_hw.o(+RO)", SCATTER)
    require(scatter, "dpls_phy6252_adc.o(+RO)", SCATTER)
    require(scatter, "dpls_phy6252_app.o(+RO)", SCATTER)

    for needle in (
        "P31 / P32 / P33", "P14 / P16 / P17",
        "+1=P20", "+2=P15", "+T=P24", "резерв=P23", "P34",
    ):
        require(checklist, needle, CHECKLIST)
    for stale in ("KZ_1 P07", "KZ_1 — P07", "P24↔GND", "сброс пароля (P24)"):
        forbid(checklist, stale, CHECKLIST)

    print("PHY6252 hardware contract: OK")


if __name__ == "__main__":
    main()
