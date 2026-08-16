#!/usr/bin/env python3
"""Measure what differential session replay can and cannot detect.

The probe freezes a reference trace from the known-good simulator, then builds
separate mutant simulators from temporary copies of firmware sources. Product
sources are never modified in-place.
"""
from __future__ import annotations

import json
import shutil
import struct
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

from differential_replay import (
    AUTH_PROOF,
    AUTH_RESULT,
    DEFAULT_PASSWORD,
    MODE_SET,
    SimulatorProcess,
    auth_proof,
    read_trace,
    replay,
)
from dpls_wire import encode_frame
from test_differential_replay import capture_reference, write_trace

HELLO = 0x01
SHORT_1 = 3


@dataclass(frozen=True)
class Mutation:
    name: str
    relative_path: str
    old: str
    new: str
    expected_killed: bool
    description: str


MUTATIONS = (
    Mutation(
        name="state-hides-dangerous-mode",
        relative_path="src/dpls_server.c",
        old="    p[0] = (uint8_t)s->safety.mode;",
        new="    p[0] = (uint8_t)DPLS_MODE_NORMAL; /* MUTANT */",
        expected_killed=True,
        description="STATE_REPORT lies that every mode is NORMAL",
    ),
    Mutation(
        name="notify-pacing-doubled",
        relative_path="phy6252_emu/phy6252_emu.h",
        old="#define PHY6252_EMU_NOTIFY_PACE_MS 80u",
        new="#define PHY6252_EMU_NOTIFY_PACE_MS 160u /* MUTANT */",
        expected_killed=True,
        description="PHY notify pacing drifts from measured 80 ms to 160 ms",
    ),
    Mutation(
        name="short1-drives-kz2",
        relative_path="sim/dpls_sim_board.c",
        old="    case DPLS_MODE_SHORT_1:\n        board->gpio_kz_1 = true;\n        break;",
        new="    case DPLS_MODE_SHORT_1:\n        board->gpio_kz_2 = true; /* MUTANT */\n        break;",
        expected_killed=False,
        description="SHORT_1 reports correctly over BLE but drives the wrong physical GPIO",
    ),
)


def mutate_file(path: Path, mutation: Mutation) -> None:
    text = path.read_text(encoding="utf-8")
    if mutation.old not in text:
        raise RuntimeError(f"mutation anchor not found: {mutation.name}: {path}")
    path.write_text(text.replace(mutation.old, mutation.new, 1), encoding="utf-8")


def build_mutant(source_firmware: Path, root: Path, mutation: Mutation) -> Path:
    firmware = root / "firmware"
    build = root / "build"
    shutil.copytree(source_firmware, firmware, ignore=shutil.ignore_patterns("build", "*.o", "*.a"))
    mutate_file(firmware / mutation.relative_path, mutation)
    cmake = root / "CMakeLists.txt"
    cmake.write_text(
        """cmake_minimum_required(VERSION 3.16)
project(dpls_mutant C)
set(CMAKE_C_STANDARD 99)
add_library(phy6252_emu firmware/phy6252_emu/phy6252_emu.c)
target_include_directories(phy6252_emu PUBLIC firmware/phy6252_emu)
target_compile_options(phy6252_emu PRIVATE -Wall -Wextra -Werror)
add_library(test_dpls_server
    firmware/src/dpls_protocol.c
    firmware/src/dpls_server.c
    firmware/src/dpls_safety.c
    firmware/src/dpls_led.c
    firmware/src/dpls_calib.c
    firmware/src/dpls_hmac.c
)
target_include_directories(test_dpls_server PUBLIC firmware/include)
target_compile_options(test_dpls_server PRIVATE -Wall -Wextra -Werror)
add_executable(dpls_simulator firmware/sim/dpls_simulator.c firmware/sim/dpls_sim_board.c)
target_include_directories(dpls_simulator PRIVATE firmware/sim)
target_link_libraries(dpls_simulator PRIVATE test_dpls_server phy6252_emu)
target_compile_options(dpls_simulator PRIVATE -Wall -Wextra -Werror)
""",
        encoding="utf-8",
    )
    subprocess.run(
        ["cmake", "-S", str(root), "-B", str(build)],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    subprocess.run(
        ["cmake", "--build", str(build), "--target", "dpls_simulator", "-j2"],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    simulator = build / "dpls_simulator"
    if not simulator.is_file():
        raise RuntimeError(f"mutant simulator not built: {mutation.name}")
    return simulator


def snapshot_after_short1(simulator: Path) -> dict[str, object]:
    sim = SimulatorProcess(simulator, environment="line")
    try:
        client_nonce = bytes(range(16))
        challenge = next(
            frame for frame in sim.frame(encode_frame(HELLO, 1, client_nonce))
            if frame.msg_type == 0x02
        )
        session_id, proof = auth_proof(DEFAULT_PASSWORD, challenge, client_nonce)
        auth_result = next(
            frame for frame in sim.frame(encode_frame(AUTH_PROOF, 2, client_nonce + proof))
            if frame.msg_type == AUTH_RESULT
        )
        if len(auth_result.payload) < 11 or auth_result.payload[0] != 0:
            raise RuntimeError("mutant authentication failed")
        token = auth_result.payload[3:11]
        auth = struct.pack("<I", session_id) + token
        sim.frame(encode_frame(MODE_SET, 3, auth + bytes([SHORT_1])))
        line = next(line for line in sim.command("SNAPSHOT") if line.startswith("SNAPSHOT "))
        return json.loads(line.removeprefix("SNAPSHOT "))
    finally:
        sim.close()


def main() -> int:
    if len(sys.argv) > 1:
        good_simulator = Path(sys.argv[1]).resolve()
    else:
        good_simulator = Path("firmware/build/dpls_simulator").resolve()
    if not good_simulator.is_file():
        print(f"good simulator not found: {good_simulator}", file=sys.stderr)
        return 2

    repo_root = Path(__file__).resolve().parents[2]
    source_firmware = repo_root / "firmware"
    required = (
        source_firmware / "src/dpls_server.c",
        source_firmware / "sim/dpls_sim_board.c",
        source_firmware / "phy6252_emu/phy6252_emu.c",
    )
    if not all(path.is_file() for path in required):
        print(f"firmware source not found: {source_firmware}", file=sys.stderr)
        return 2

    observable_total = 0
    observable_killed = 0
    failures: list[str] = []

    with tempfile.TemporaryDirectory(prefix="dpls-mutation-probe-") as temp_dir:
        temp = Path(temp_dir)
        trace_path = temp / "baseline.frames.txt"
        write_trace(trace_path, capture_reference(good_simulator), corrupt_session=True)
        trace = read_trace(trace_path)

        for index, mutation in enumerate(MUTATIONS):
            mutant_root = temp / f"mutant-{index}-{mutation.name}"
            try:
                mutant_simulator = build_mutant(source_firmware, mutant_root, mutation)
                compared, expected, mismatches = replay(
                    trace,
                    mutant_simulator,
                    DEFAULT_PASSWORD,
                    "line",
                    verbose=False,
                )
            except (OSError, RuntimeError, subprocess.CalledProcessError) as exc:
                failures.append(f"{mutation.name}: probe failed: {exc}")
                continue

            killed = bool(mismatches)
            verdict = "KILLED" if killed else "SURVIVED"
            print(
                f"{verdict:8} {mutation.name}: compared={compared}/{expected} "
                f"mismatches={len(mismatches)} — {mutation.description}",
            )
            for mismatch in mismatches[:3]:
                print(f"  {mismatch}")

            if mutation.expected_killed:
                observable_total += 1
                if killed:
                    observable_killed += 1
                else:
                    failures.append(f"{mutation.name}: observable mutant survived")
            else:
                if killed:
                    failures.append(f"{mutation.name}: known BLE blind spot was unexpectedly killed")
                snapshot = snapshot_after_short1(mutant_simulator)
                gpio = snapshot.get("gpio", {})
                if not isinstance(gpio, dict) or gpio.get("kz1") != 0 or gpio.get("kz2") != 1:
                    failures.append(f"{mutation.name}: mutant did not actually drive KZ2 instead of KZ1")
                else:
                    print("  confirmed hidden fault: mode=SHORT_1 while gpio.kz1=0 gpio.kz2=1")

    print(f"observable mutation score: {observable_killed}/{observable_total}")
    print("known blind spot: physical GPIO divergence is invisible to a BLE-only capture")
    if failures:
        for failure in failures:
            print(f"FAIL {failure}", file=sys.stderr)
        return 1
    print("mutation probe: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
