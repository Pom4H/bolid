#!/usr/bin/env python3
"""Автоматическая проверка ADC/BLE soak по UART PB-03F.

Прошить ветку fix/phy6252-adc-watchdog, запустить плату и затем:

    python3 tools/adc_soak_check.py 900
    python3 tools/adc_soak_check.py 7200 --port /dev/cu.wchusbserial110

Скрипт пропускает UART в консоль и завершает работу с кодом 0 только если:
- были успешные ADC samples;
- счётчик completed рос с ожидаемой скоростью;
- не встречались ADC timeout/config/start errors;
- после начала ADC-серии не было нового boot/reset marker.
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import sys
import time

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_ROOT, ".python-deps"))

import serial  # noqa: E402

OK_RE = re.compile(r"\[ADC SOAK\] ok n=(\d+)")
ERROR_MARKERS = (
    "[ADC SOAK] TIMEOUT",
    "[ADC SOAK] config rc=",
    "[ADC SOAK] start rc=",
)
BOOT_MARKERS = (
    "[REST CAUSE]",
    "SDK Version ID",
)


def default_port() -> str:
    ports = sorted(glob.glob("/dev/cu.wchusbserial*") + glob.glob("/dev/ttyUSB*"))
    if not ports:
        raise SystemExit("USB-UART адаптер не найден; укажите --port")
    return ports[0]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("duration", nargs="?", type=float, default=900.0, help="секунды, по умолчанию 900")
    parser.add_argument("--port", default=None)
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument(
        "--min-ratio",
        type=float,
        default=0.75,
        help="минимальная доля completed/секунда после 3-секундного старта",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    port = args.port or default_port()
    serial_port = serial.Serial(port, args.baud, timeout=0.2)
    serial_port.setDTR(False)
    serial_port.setRTS(False)
    serial_port.reset_input_buffer()

    started_at = time.monotonic()
    text_buffer = ""
    first_n: int | None = None
    last_n: int | None = None
    first_ok_at: float | None = None
    errors: list[str] = []
    saw_soak = False

    print(f"ADC soak: {args.duration:.0f}s, {port} @ {args.baud}", flush=True)
    try:
        while time.monotonic() - started_at < args.duration:
            chunk = serial_port.read(4096)
            if not chunk:
                continue
            text = chunk.decode("utf-8", errors="replace")
            sys.stdout.write(text)
            sys.stdout.flush()
            text_buffer += text

            while "\n" in text_buffer:
                line, text_buffer = text_buffer.split("\n", 1)
                match = OK_RE.search(line)
                if match:
                    value = int(match.group(1))
                    if first_n is None:
                        first_n = value
                        first_ok_at = time.monotonic()
                    if last_n is not None and value <= last_n:
                        errors.append(f"ADC counter did not grow: {last_n} -> {value}")
                    last_n = value
                    saw_soak = True

                for marker in ERROR_MARKERS:
                    if marker in line:
                        errors.append(line.strip())

                # One boot before the first sample is allowed: the operator may
                # have started capture and then pressed KEY1. Any boot after ADC
                # activity is an unambiguous reset during the soak.
                if saw_soak and any(marker in line for marker in BOOT_MARKERS):
                    errors.append(f"reset after ADC started: {line.strip()}")
    except KeyboardInterrupt:
        print("\nInterrupted; evaluating captured interval", flush=True)
    finally:
        serial_port.close()

    elapsed = time.monotonic() - started_at
    if first_n is None or last_n is None or first_ok_at is None:
        errors.append("no '[ADC SOAK] ok' records received")
    else:
        measured_seconds = max(1.0, time.monotonic() - first_ok_at)
        growth = last_n - first_n
        expected_min = max(1, int(measured_seconds * args.min_ratio))
        if growth < expected_min:
            errors.append(
                f"too few completed conversions: growth={growth}, expected>={expected_min} "
                f"over {measured_seconds:.1f}s"
            )

    print("\n--- ADC soak verdict ---")
    print(f"elapsed={elapsed:.1f}s first_n={first_n} last_n={last_n}")
    if errors:
        for error in errors:
            print(f"FAIL: {error}")
        return 1
    print("PASS: no reset/timeout/error; ADC counter grows normally")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
