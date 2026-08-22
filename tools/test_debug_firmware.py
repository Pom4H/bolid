#!/usr/bin/env python3
"""Static contract for the opt-in PHY6252 UART/power diagnostic image."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "tools/build_debug_firmware.sh"
FLASH = ROOT / "tools/flash_debug_firmware.sh"
TARGET = ROOT / "firmware/targets/phy6252/source/dplsBLEPeripheral.c"
RUNTIME = ROOT / "firmware/phy6252/dpls_phy6252_runtime.c"
STORAGE = ROOT / "firmware/phy6252/dpls_phy6252_storage.c"
MAKEFILE = ROOT / "firmware/targets/phy6252/Makefile"
CPROJECT = ROOT / "firmware/targets/phy6252/test-dpls.cproject.yml"
LINKER = ROOT / "firmware/targets/phy6252/phy6252.ld"
EVENTS = ROOT / "firmware/phy6252/dpls_phy6252_events.h"

subprocess.run(["bash", "-n", str(BUILD)], check=True)
subprocess.run(["bash", "-n", str(FLASH)], check=True)
build = BUILD.read_text(encoding="utf-8")
flash = FLASH.read_text(encoding="utf-8")
target = TARGET.read_text(encoding="utf-8")
runtime = RUNTIME.read_text(encoding="utf-8")
storage = STORAGE.read_text(encoding="utf-8")
makefile = MAKEFILE.read_text(encoding="utf-8")
cproject = CPROJECT.read_text(encoding="utf-8")
linker = LINKER.read_text(encoding="utf-8")
events = EVENTS.read_text(encoding="utf-8")

for token in (
    "DEBUG_INFO=1", "DPLS_DEBUG_UART_ROM=1", "DPLS_POWER_DIAG_LOG=1",
    "build-debug-rom", '"power diagnostics": b"DPLS PWR t="',
):
    assert token in build, token
for token in (
    "--initial-manual",
    "--application-handoff-token",
    "00d544504c532d524f4da55ac33c7e81",
    'BOOT_START="0x1fff1838"',
    "--bin phy6252-flash",
):
    assert token in flash, token
for token in ("'D', 'P', 'L', 'S', '-', 'R', 'O', 'M'", "debug_uart_rx", "debug_uart_wake", "DPLS ROM PREPARE"):
    assert token in target, token
for token in ("dpls_phy6252_runtime_request_rom_boot", "DPLS PWR t=", "NVIC_SystemReset"):
    assert token in runtime, token
for token in ("DPLS_ROM_BOOTINFO_PART_COUNT_ADDR", "flash_write_word", "verify == 0u"):
    assert token in storage, token
for token in ("DPLS_DEBUG_UART_ROM ?= 0", "DPLS_POWER_DIAG_LOG ?= 0"):
    assert token in makefile, token
for token in ('DPLS_DEBUG_UART_ROM: "0"', 'DPLS_POWER_DIAG_LOG: "0"'):
    assert token in cproject, token
for token in ("*uart.o(.text.hal_uart_deinit)", "*pwrmgr.o(.text.hal_pwrmgr_unregister)"):
    assert token in linker, token
assert "DEBUG_UART_SLEEP_EVT 0x8000" not in events  # SYS_EVENT_MSG owns 0x8000.

print("PHY6252 diagnostic builder: PASS")
