#!/usr/bin/env python3
"""Record a live Test-DPLS session from phone logcat (+ optional UART).

Writes a timestamped multiplexed text log under tmp/sessions/ for later parse/replay.

Examples:
  python3 tools/session_capture/record_session.py
  python3 tools/session_capture/record_session.py --serial /dev/ttyUSB0 --duration 120
  python3 tools/session_capture/record_session.py --adb-serial emulator-5554
"""
from __future__ import annotations

import argparse
import datetime as dt
import os
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT_DIR = ROOT / "tmp" / "sessions"
LOGCAT_TAGS = (
    "TestDplsBle:I",
    "TestDplsSession:I",
    "TestDplsE2e:I",
    "TestDplsSim:I",
)


class TeeWriter:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._fh = self.path.open("w", encoding="utf-8", buffering=1)
        self._lock = threading.Lock()

    def write(self, source: str, line: str) -> None:
        stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
        text = line.rstrip("\n")
        row = f"{stamp}\t{source}\t{text}\n"
        with self._lock:
            self._fh.write(row)
            self._fh.flush()
            sys.stdout.write(row)
            sys.stdout.flush()

    def close(self) -> None:
        with self._lock:
            self._fh.close()


def adb_base(serial: str | None) -> list[str]:
    cmd = ["adb"]
    if serial:
        cmd.extend(["-s", serial])
    return cmd


def stream_logcat(writer: TeeWriter, serial: str | None, stop: threading.Event) -> None:
    clear = adb_base(serial) + ["logcat", "-c"]
    subprocess.run(clear, check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    cmd = adb_base(serial) + ["logcat", "-v", "threadtime", "-s", *LOGCAT_TAGS]
    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    assert proc.stdout is not None
    try:
        while not stop.is_set():
            line = proc.stdout.readline()
            if line == "" and proc.poll() is not None:
                break
            if line:
                writer.write("logcat", line)
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            proc.kill()


def stream_serial(writer: TeeWriter, port: str, baud: int, stop: threading.Event) -> None:
    sys.path.insert(0, str(ROOT / ".python-deps"))
    try:
        import serial  # type: ignore
    except ImportError:
        writer.write("meta", "ERROR serial module missing; install pyserial in .python-deps")
        return
    try:
        ser = serial.Serial(port, baud, timeout=0.2)
    except Exception as exc:  # noqa: BLE001 — surface to capture log
        writer.write("meta", f"ERROR open serial {port}: {exc}")
        return
    writer.write("meta", f"UART open {port}@{baud}")
    try:
        while not stop.is_set():
            chunk = ser.read(4096)
            if not chunk:
                continue
            text = chunk.decode("utf-8", errors="replace")
            for line in text.splitlines():
                if line.strip():
                    writer.write("uart", line)
    finally:
        ser.close()
        writer.write("meta", "UART closed")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument("--name", default="", help="optional session name suffix")
    parser.add_argument("--duration", type=float, default=0.0, help="seconds; 0 = until Ctrl-C")
    parser.add_argument("--adb-serial", default=None)
    parser.add_argument("--serial", default=None, help="optional UART device path")
    parser.add_argument("--baud", type=int, default=115200)
    args = parser.parse_args()

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    suffix = f"-{args.name}" if args.name else ""
    out = args.out_dir / f"session-{stamp}{suffix}.log"
    writer = TeeWriter(out)
    writer.write("meta", f"session_start out={out}")
    writer.write("meta", "tags=" + ",".join(LOGCAT_TAGS))

    stop = threading.Event()

    def handle_sig(_signum: int, _frame: object) -> None:
        stop.set()

    signal.signal(signal.SIGINT, handle_sig)
    signal.signal(signal.SIGTERM, handle_sig)

    threads = [
        threading.Thread(target=stream_logcat, args=(writer, args.adb_serial, stop), daemon=True),
    ]
    if args.serial:
        threads.append(
            threading.Thread(
                target=stream_serial,
                args=(writer, args.serial, args.baud, stop),
                daemon=True,
            ),
        )
    for thread in threads:
        thread.start()

    deadline = time.time() + args.duration if args.duration > 0 else None
    try:
        while not stop.is_set():
            if deadline is not None and time.time() >= deadline:
                stop.set()
                break
            time.sleep(0.2)
    finally:
        stop.set()
        for thread in threads:
            thread.join(timeout=3)
        writer.write("meta", "session_end")
        writer.close()
        print(f"wrote {out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
