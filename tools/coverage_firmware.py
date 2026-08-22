#!/usr/bin/env python3
"""Line-coverage gate for the complete portable firmware core.

Reads gcov notes produced next to instrumented objects and fails if aggregated
coverage of production firmware/src drops below the threshold. Safety,
cryptography and durable storage are deliberately included; excluding critical
modules would make a green release gate misleading.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

DEFAULT_THRESHOLD = 80.0
SRC_NAMES = (
    "dpls_protocol.c",
    "dpls_server.c",
    "dpls_safety.c",
    "dpls_led.c",
    "dpls_calib.c",
    "dpls_hmac.c",
    "dpls_durable_settings.c",
)


def find_gcov() -> list[str]:
    override = os.environ.get("GCOV")
    if override:
        return override.split()
    if sys.platform == "darwin":
        try:
            xcrun = subprocess.run(
                ["xcrun", "--find", "llvm-cov"],
                capture_output=True,
                text=True,
                check=False,
            )
        except FileNotFoundError:
            xcrun = None
        if xcrun is not None and xcrun.returncode == 0:
            llvm_cov = xcrun.stdout.strip()
            if llvm_cov:
                return [llvm_cov, "gcov"]
    llvm_cov = shutil.which("llvm-cov")
    if llvm_cov:
        return [llvm_cov, "gcov"]
    gcov = shutil.which("gcov")
    if gcov:
        return [gcov]
    raise SystemExit("gcov / llvm-cov not found")


def parse_gcov_i(text: str) -> tuple[int, int]:
    hit = 0
    total = 0
    for line in text.splitlines():
        if not line.startswith("lcount:"):
            continue
        _, rest = line.split(":", 1)
        parts = rest.split(",")
        if len(parts) < 2:
            continue
        try:
            count = int(parts[1])
        except ValueError:
            continue
        total += 1
        if count > 0:
            hit += 1
    return hit, total


def collect(build_dir: Path) -> dict[str, tuple[int, int]]:
    gcov = find_gcov()
    results: dict[str, tuple[int, int]] = {}
    gcda_files = sorted(build_dir.rglob("*.gcda"))
    if not gcda_files:
        raise SystemExit(f"no .gcda files under {build_dir}; rebuild with -DENABLE_COVERAGE=ON")

    tmp = build_dir / "gcov-out"
    tmp.mkdir(exist_ok=True)
    for gcda in gcda_files:
        name = gcda.stem
        src = name.replace(".c", "") + ".c" if not name.endswith(".c") else name
        if src not in SRC_NAMES and name not in SRC_NAMES:
            continue
        key = src if src in SRC_NAMES else name
        proc = subprocess.run(
            gcov + ["-i", "-o", str(gcda.parent), str(gcda)],
            cwd=tmp,
            capture_output=True,
            text=True,
        )
        gcov_notes = list(tmp.glob("*.gcov"))
        text = ""
        for note in gcov_notes:
            text += note.read_text(errors="replace")
            note.unlink(missing_ok=True)
        if not text:
            text = proc.stdout + proc.stderr
        hit, total = parse_gcov_i(text)
        if total == 0:
            classic = subprocess.run(
                gcov + ["-o", str(gcda.parent), str(gcda)],
                cwd=tmp,
                capture_output=True,
                text=True,
            )
            for note in tmp.glob("*.gcov"):
                h, t = parse_classic_gcov(note.read_text(errors="replace"))
                hit += h
                total += t
                note.unlink(missing_ok=True)
            if total == 0:
                sys.stderr.write(classic.stdout + classic.stderr)
        if key in results:
            prev_h, prev_t = results[key]
            results[key] = (prev_h + hit, prev_t + total)
        else:
            results[key] = (hit, total)
    return results


def parse_classic_gcov(text: str) -> tuple[int, int]:
    hit = 0
    total = 0
    for line in text.splitlines():
        if ":" not in line:
            continue
        count, _rest = line.split(":", 1)
        count = count.strip()
        if count in ("-", ""):
            continue
        if count == "#####":
            total += 1
            continue
        try:
            n = int(count)
        except ValueError:
            continue
        total += 1
        if n > 0:
            hit += 1
    return hit, total


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-dir", default="firmware/build-cov")
    parser.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    parser.add_argument("--report", default="tmp/firmware-coverage.txt")
    args = parser.parse_args()

    root = Path(__file__).resolve().parent.parent
    build_dir = (root / args.build_dir).resolve() if not os.path.isabs(args.build_dir) else Path(args.build_dir)
    results = collect(build_dir)
    if not results:
        raise SystemExit("no coverage data for firmware/src")

    lines = []
    hit_sum = 0
    total_sum = 0
    for name in SRC_NAMES:
        hit, total = results.get(name, (0, 0))
        pct = (100.0 * hit / total) if total else 0.0
        lines.append(f"  {name:24} {hit:4}/{total:<4}  {pct:5.1f}%")
        hit_sum += hit
        total_sum += total
    overall = (100.0 * hit_sum / total_sum) if total_sum else 0.0
    report = (
        "firmware/src line coverage\n"
        + "\n".join(lines)
        + f"\n  {'TOTAL':24} {hit_sum:4}/{total_sum:<4}  {overall:5.1f}%\n"
        + f"threshold: {args.threshold:.0f}%\n"
    )
    report_path = root / args.report
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report)
    sys.stdout.write(report)
    if overall + 1e-9 < args.threshold:
        sys.stderr.write(f"error: coverage {overall:.1f}% is below {args.threshold:.0f}%\n")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
