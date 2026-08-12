#!/usr/bin/env python3
"""Fail the build when the PHY6252 hardware contract drifts.

This is intentionally stricter than a collection of grep checks.  The pin map is
an electrical interface: moving a symbol to another pad, overlapping an ADC with
a power-stage output, or silently dropping the BLE sleep lock must require an
explicit change to this contract in the same review.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BOARD = ROOT / "Firmware/phy6252/dpls_board.h"
HW = ROOT / "Firmware/phy6252/dpls_phy6252_hw.c"
ADC = ROOT / "Firmware/phy6252/dpls_phy6252_adc.c"
APP = ROOT / "Firmware/phy6252/dpls_phy6252_app.c"
TARGET = ROOT / "Firmware/targets/phy6252/source/dplsBLEPeripheral.c"
CPROJECT = ROOT / "Firmware/targets/phy6252/test-dpls.cproject.yml"
CHECKLIST = ROOT / "docs/bring-up-checklist.md"

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


def main() -> None:
    board = read(BOARD)
    hw = read(HW)
    adc = read(ADC)
    app = read(APP)
    target = read(TARGET)
    cproject = read(CPROJECT)
    checklist = read(CHECKLIST)

    defs = {name: value.strip() for name, value in DEFINE_RE.findall(board)}
    resolved = {name: resolve_pin(name, defs) for name in EXPECTED}
    if resolved != EXPECTED:
        differences = [
            f"{name}: expected {EXPECTED[name]}, got {resolved.get(name, '<missing>')}"
            for name in EXPECTED
            if resolved.get(name) != EXPECTED[name]
        ]
        fail("pin map changed:\n  " + "\n  ".join(differences))

    # All physical roles are intentionally unique.  The only board alias is the
    # backwards-compatible LINE_ADC -> PORT1_ADC symbol, not another role.
    by_pin: dict[str, list[str]] = {}
    for role, pin in resolved.items():
        by_pin.setdefault(pin, []).append(role)
    collisions = {pin: roles for pin, roles in by_pin.items() if len(roles) != 1}
    if collisions:
        fail(f"physical pin overlap: {collisions}")
    if resolve_pin("DPLS_PIN_LINE_ADC", defs) != EXPECTED["DPLS_PIN_PORT1_ADC"]:
        fail("DPLS_PIN_LINE_ADC must remain an alias of DPLS_PIN_PORT1_ADC")

    # Digital hardware has exactly one owner.
    require(hw, "hal_pwrmgr_register(MOD_USR1, NULL, disable_32k_xtal)", HW)
    require(hw, "hal_pwrmgr_lock(MOD_USR1)", HW)
    require(hw, "hal_pwrmgr_unlock(MOD_USR1)", HW)
    require(hw, "prime_all_outputs_low", HW)
    require(hw, "register_output_retention", HW)
    require(hw, "subWriteReg(&(AP_AON->PMCTL0), 28, 28, 0x00)", HW)
    forbid(hw, "MOD_USR2", HW)
    for symbol in (
        "DPLS_PIN_ISO_1", "DPLS_PIN_ISO_2", "DPLS_PIN_ISO_T",
        "DPLS_PIN_KZ_1", "DPLS_PIN_KZ_2", "DPLS_PIN_KZ_T",
        "DPLS_PIN_LED_RED", "DPLS_PIN_LED_GREEN", "DPLS_PIN_LED_BLUE",
    ):
        require(hw, symbol, HW)

    # ADC mux and callback channel are paired explicitly for each external net.
    for needle in (
        "ADC_BIT(ADC_CH3P_P20)", "result_channel = ADC_CH9",
        "ADC_BIT(ADC_CH3N_P15)", "result_channel = ADC_CH4",
        "ADC_BIT(ADC_CH2N_P24)", "result_channel = ADC_CH2",
        "ADC_BIT(ADC_CH1P_P23)", "result_channel = ADC_CH1",
        "DPLS_ADC_NEED_ALL", "DPLS_ADC_STALE_MS",
        "dpls_adc_assert_port1", "dpls_adc_assert_port2",
        "dpls_adc_assert_port_t", "dpls_adc_assert_vcap",
    ):
        require(adc, needle, ADC)
    forbid(adc, "ADC_CH1N_P11", ADC)

    # Application code consumes hardware services; it may not reopen their
    # low-level implementation details.
    for needle in (
        "hal.port1_voltage_mv = port1_voltage_mv",
        "hal.port2_voltage_mv = port2_voltage_mv",
        "hal.port_t_voltage_mv = port_t_voltage_mv",
        "hal.reserve_voltage_mv = reserve_voltage_mv",
        "dpls_phy6252_hw_connection_lock()",
        "dpls_phy6252_adc_tick(now)",
    ):
        require(app, needle, APP)
    for forbidden in (
        "hal_pwrmgr_register(", "hal_pwrmgr_lock(", "hal_adc_config_channel(",
        "hal_adc_start(", "hal_gpio_pin_init(DPLS_PIN_ISO", "hal_gpio_pin_init(DPLS_PIN_KZ",
    ):
        forbid(app, forbidden, APP)

    # The BLE target is integration glue only; board-specific safety belongs to
    # dpls_phy6252_hw.c.
    require(target, "dpls_phy6252_hw_init()", TARGET)
    for forbidden in ("MOD_USR1", "MOD_USR2", "disable_32k_xtal", "prime_safe_gpio_outputs"):
        forbid(target, forbidden, TARGET)

    require(cproject, "../../phy6252/dpls_phy6252_hw.c", CPROJECT)
    require(cproject, "../../phy6252/dpls_phy6252_adc.c", CPROJECT)

    # Human bring-up instructions are part of the hardware interface too.
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
