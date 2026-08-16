#!/usr/bin/env python3
"""Send `jfill` over the kit UART so firmware seeds a 14-day demo journal."""
import glob
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 2)[0] + "/.python-deps")
import serial  # noqa: E402


def port() -> str:
    if "--port" in sys.argv:
        return sys.argv[sys.argv.index("--port") + 1]
    found = sorted(glob.glob("/dev/cu.wchusbserial*"))
    if not found:
        raise SystemExit("USB-UART адаптер (CH340) не найден")
    return found[0]


p = serial.Serial(port(), 115200, timeout=0.2)
p.setDTR(False)
p.setRTS(False)
p.reset_input_buffer()
p.write(b"jfill\r\n")
p.flush()
deadline = time.time() + 25
buf = b""
while time.time() < deadline:
    chunk = p.read(4096)
    if chunk:
        buf += chunk
        sys.stdout.write(chunk.decode("utf-8", errors="replace"))
        sys.stdout.flush()
        if b"journal fill" in buf:
            p.close()
            sys.exit(0)
p.close()
raise SystemExit("плата не ответила journal fill — нужна прошивка с командой jfill")
