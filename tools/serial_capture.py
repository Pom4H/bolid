#!/usr/bin/env python3
"""Захват UART-лога платы PB-03F (115200), опционально с попыткой RTS-сброса.

Использование:
    python3 tools/serial_capture.py [секунды] [--no-reset] [--port /dev/cu.xxx]

На ките RTS/DTR к плате не разведены, так что --no-reset + короткое
нажатие KEY1 (power-cycle) — основной способ поймать лог загрузки.
"""
import glob
import os
import sys
import time

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_ROOT, ".python-deps"))
import serial  # noqa: E402


def default_port() -> str:
    ports = sorted(glob.glob("/dev/cu.wchusbserial*"))
    if not ports:
        raise SystemExit("USB-UART адаптер (CH340) не найден")
    return ports[0]


PORT = default_port()
if "--port" in sys.argv:
    PORT = sys.argv[sys.argv.index("--port") + 1]
DURATION = next((float(a) for a in sys.argv[1:] if a.replace(".", "").isdigit()), 8.0)
RESET = "--no-reset" not in sys.argv

p = serial.Serial(PORT, 115200, timeout=0.2)
p.setDTR(False)  # TM отпущен -> обычная загрузка
if RESET:
    p.setRTS(True)  # RSTN low (если разведён)
    time.sleep(0.15)
    p.reset_input_buffer()
    p.setRTS(False)
else:
    p.setRTS(False)
    p.reset_input_buffer()

start = time.time()
total = 0
while time.time() - start < DURATION:
    chunk = p.read(4096)
    if chunk:
        total += len(chunk)
        sys.stdout.write(chunk.decode("utf-8", errors="replace"))
        sys.stdout.flush()
p.close()
print(f"\n--- captured {total} bytes in {DURATION:.0f}s from {PORT} ---", flush=True)
