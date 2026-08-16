#!/usr/bin/env python3
"""E2E regression for differential_replay.py using the real C dpls_simulator."""
from __future__ import annotations

import os
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

from differential_replay import (
    AUTH_PREFIX_TYPES,
    AUTH_PROOF,
    AUTH_RESULT,
    COMMAND_RESULT,
    DEFAULT_PASSWORD,
    MODE_SET,
    STATE_GET,
    STATE_REPORT,
    SimulatorProcess,
    auth_proof,
)
from dpls_wire import Frame, decode_frame, encode_frame

HELLO = 0x01
SHORT_1 = 3
NORMAL = 0


def one_response(sim: SimulatorProcess, raw: bytes, expected_type: int) -> Frame:
    frames = sim.frame(raw)
    matches = [frame for frame in frames if frame.msg_type == expected_type]
    if len(matches) != 1:
        raise AssertionError(
            f"expected one response type=0x{expected_type:02x}, "
            f"got {[frame.type_name for frame in frames]}",
        )
    return matches[0]


def capture_reference(simulator: Path) -> list[tuple[str, Frame, bytes]]:
    sim = SimulatorProcess(simulator, environment="line")
    records: list[tuple[str, Frame, bytes]] = []
    try:
        client_nonce = bytes(range(16))

        hello_raw = encode_frame(HELLO, 1, client_nonce)
        hello = decode_frame(hello_raw)
        assert hello is not None
        challenge = one_response(sim, hello_raw, 0x02)
        records.extend((("TX", hello, hello_raw), ("RX", challenge, encode_frame(
            challenge.msg_type,
            challenge.sequence,
            challenge.payload,
            flags=challenge.flags,
        ))))

        session_id, proof = auth_proof(DEFAULT_PASSWORD, challenge, client_nonce)
        proof_raw = encode_frame(AUTH_PROOF, 2, client_nonce + proof)
        proof_frame = decode_frame(proof_raw)
        assert proof_frame is not None
        auth_result = one_response(sim, proof_raw, AUTH_RESULT)
        if len(auth_result.payload) < 11 or auth_result.payload[0] != 0:
            raise AssertionError(f"authentication failed: {auth_result.payload.hex()}")
        token = auth_result.payload[3:11]
        records.extend((("TX", proof_frame, proof_raw), ("RX", auth_result, encode_frame(
            auth_result.msg_type,
            auth_result.sequence,
            auth_result.payload,
            flags=auth_result.flags,
        ))))

        auth = struct.pack("<I", session_id) + token

        def exchange(msg_type: int, sequence: int, payload: bytes, expected_type: int) -> Frame:
            raw = encode_frame(msg_type, sequence, payload)
            request = decode_frame(raw)
            assert request is not None
            response = one_response(sim, raw, expected_type)
            records.append(("TX", request, raw))
            records.append((
                "RX",
                response,
                encode_frame(response.msg_type, response.sequence, response.payload, flags=response.flags),
            ))
            return response

        exchange(STATE_GET, 3, auth, STATE_REPORT)
        command = exchange(MODE_SET, 4, auth + bytes([SHORT_1]), COMMAND_RESULT)
        if len(command.payload) < 2 or command.payload[0] != 0 or command.payload[1] != SHORT_1:
            raise AssertionError(f"SHORT_1 was not applied: {command.payload.hex()}")
        state_short = exchange(STATE_GET, 5, auth, STATE_REPORT)
        if not state_short.payload or state_short.payload[0] != SHORT_1:
            raise AssertionError(f"STATE_REPORT did not expose SHORT_1: {state_short.payload.hex()}")
        exchange(MODE_SET, 6, auth + bytes([NORMAL]), COMMAND_RESULT)
        state_normal = exchange(STATE_GET, 7, auth, STATE_REPORT)
        if not state_normal.payload or state_normal.payload[0] != NORMAL:
            raise AssertionError(f"STATE_REPORT did not return to NORMAL: {state_normal.payload.hex()}")
        return records
    finally:
        sim.close()


def write_trace(path: Path, records: list[tuple[str, Frame, bytes]], corrupt_session: bool) -> None:
    lines: list[str] = []
    for direction, frame, raw in records:
        output = raw
        if corrupt_session and direction == "TX":
            if frame.msg_type == AUTH_PROOF and len(frame.payload) >= 48:
                # A captured proof is tied to the original board challenge. Deliberately
                # poison it: differential_replay must regenerate it for the fresh sim.
                payload = frame.payload[:16] + bytes([0xA5]) * 32
                output = encode_frame(frame.msg_type, frame.sequence, payload, flags=frame.flags)
            elif frame.msg_type in AUTH_PREFIX_TYPES and len(frame.payload) >= 12:
                # Same for authenticated commands: captured sessionId/token must never
                # be trusted when replaying against another device instance.
                payload = bytes.fromhex("EFBEADDE0102030405060708") + frame.payload[12:]
                output = encode_frame(frame.msg_type, frame.sequence, payload, flags=frame.flags)
        lines.append(f"{direction} {frame.type_name} {output.hex().upper()}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_semantic_mismatch(
    source: Path,
    target: Path,
) -> None:
    lines = source.read_text(encoding="utf-8").splitlines()
    for index in range(len(lines) - 1, -1, -1):
        parts = lines[index].split()
        if len(parts) < 3 or parts[0] != "RX":
            continue
        raw = bytes.fromhex(parts[-1])
        frame = decode_frame(raw)
        if frame is None or frame.msg_type != STATE_REPORT or not frame.payload:
            continue
        payload = bytearray(frame.payload)
        payload[0] = SHORT_1  # reference ends in NORMAL; make oracle intentionally wrong.
        bad = encode_frame(frame.msg_type, frame.sequence, bytes(payload), flags=frame.flags)
        lines[index] = f"RX {frame.type_name} {bad.hex().upper()}"
        target.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return
    raise AssertionError("no STATE_REPORT found to corrupt")


def run_replay(tool: Path, trace: Path, simulator: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(tool),
            str(trace),
            "--simulator",
            str(simulator),
            "--password",
            DEFAULT_PASSWORD,
            "--environment",
            "line",
            "--verbose",
        ],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )


def main() -> int:
    simulator_env = os.environ.get("DPLS_SIMULATOR")
    if not simulator_env:
        print("SKIP: set DPLS_SIMULATOR to run differential replay E2E")
        return 0
    simulator = Path(simulator_env).resolve()
    if not simulator.is_file():
        print(f"DPLS_SIMULATOR does not exist: {simulator}", file=sys.stderr)
        return 2

    here = Path(__file__).resolve().parent
    tool = here / "differential_replay.py"
    records = capture_reference(simulator)

    with tempfile.TemporaryDirectory(prefix="dpls-diff-") as temp_dir:
        temp = Path(temp_dir)
        good = temp / "captured.frames.txt"
        bad = temp / "captured-mismatch.frames.txt"
        write_trace(good, records, corrupt_session=True)

        result = run_replay(tool, good, simulator)
        print(result.stdout, end="")
        if result.returncode != 0:
            print("FAIL: transplanted real-session shape did not replay cleanly", file=sys.stderr)
            return 1
        if "mismatches=0" not in result.stdout:
            print("FAIL: clean replay did not report mismatches=0", file=sys.stderr)
            return 1

        write_semantic_mismatch(good, bad)
        mismatch = run_replay(tool, bad, simulator)
        print(mismatch.stdout, end="")
        if mismatch.returncode != 1:
            print("FAIL: semantic mismatch was not detected", file=sys.stderr)
            return 1
        if "MISMATCH" not in mismatch.stdout or "STATE_GET" not in mismatch.stdout:
            print("FAIL: mismatch output did not identify the divergent state request", file=sys.stderr)
            return 1

    print("differential replay E2E: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
