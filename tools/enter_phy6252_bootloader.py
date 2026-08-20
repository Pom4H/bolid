#!/usr/bin/env python3
"""Ask Test-DPLS firmware to reset into the PHY62x2 ROM programmer."""

import argparse
import sys
import time

import serial


TOKEN = bytes((
    0x00, 0xD5, ord("D"), ord("P"), ord("L"), ord("S"), ord("-"),
    ord("R"), ord("O"), ord("M"), 0xA5, 0x5A, 0xC3, 0x3C, 0x7E, 0x81,
))
ROM_SYNC = b"UXTDWU"


def rom_ready_at_115200(uart: serial.Serial) -> bool:
    uart.baudrate = 115200
    uart.timeout = 0.20
    uart.reset_input_buffer()
    uart.write(b"rdrev+ ")
    reply = uart.read(26)
    return reply.endswith(b"#OK>>:")


def request_bootloader(port: str, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    with serial.Serial(port, 115200, timeout=0.05) as uart:
        uart.dtr = False
        uart.rts = False
        uart.reset_input_buffer()
        # Recovery after a previous interrupted flash: ROM may already be
        # waiting at the run baud with an invalid application header.
        if rom_ready_at_115200(uart):
            print("Уже открытый ROM-загрузчик найден на 115200", file=sys.stderr)
            return True
        # P10 doubles as the low-power wake input while UART0 is clock-gated.
        # The break wakes the SoC; it is intentionally not part of TOKEN.
        uart.send_break(duration=0.06)
        time.sleep(0.15)
        while time.monotonic() < deadline:
            # A legacy image may only have UART0 alive during a very short
            # periodic wake window. A continuous train guarantees that at
            # least one complete framed token lands inside that window.
            uart.write(TOKEN * 32)
            uart.flush()
            # Arm ROM sync before the USB-delayed ACK can arrive. Waiting for
            # ACK here is already too late for the ROM's short listen window.
            uart.baudrate = 9600
            uart.timeout = 0.02
            rom_reply = bytearray()
            for _ in range(12):
                uart.write(ROM_SYNC)
                chunk = uart.read(64)
                if chunk:
                    rom_reply.extend(chunk)
                    if b"cmd>>:" in rom_reply:
                        print("ROM-загрузчик синхронизирован", file=sys.stderr)
                        uart.reset_input_buffer()
                        uart.write(b"uarts115200")
                        time.sleep(0.10)
                        # This PHY6222 ROM revision changes baud reliably but
                        # does not return #OK at the old speed. Verify at the
                        # new speed and leave ROM ready for rdwr -n.
                        if rom_ready_at_115200(uart):
                            return True
                        print("ROM не отвечает после смены скорости", file=sys.stderr)
                        return False
                    if len(rom_reply) > 128:
                        del rom_reply[:-64]
            uart.baudrate = 115200
            uart.timeout = 0.05
    return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", required=True)
    parser.add_argument("--timeout", type=float, default=7.0)
    args = parser.parse_args()
    try:
        return 0 if request_bootloader(args.port, args.timeout) else 2
    except serial.SerialException as error:
        print(f"Не удалось открыть {args.port}: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
