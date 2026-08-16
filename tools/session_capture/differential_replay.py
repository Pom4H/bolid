#!/usr/bin/env python3
"""Differentially replay a captured DPLS v2 session against dpls_simulator.

Unlike a byte-for-byte replay, this tool transplants the captured request stream
onto the simulator's fresh authenticated session:

- HELLO keeps the captured client nonce;
- AUTH_PROOF is recomputed from the simulator challenge and the supplied password;
- authenticated requests get the simulator sessionId + token prefix;
- response comparison ignores intentionally random/session-local bytes and compares
  stable protocol semantics (status, mode, power, reserve/short flags, etc.).

A real phone↔PHY6252 capture can therefore become a repeatable simulator
regression instead of failing immediately because nonces/tokens are different.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import struct
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

from dpls_wire import (
    FLAG_ERROR,
    FLAG_EVENT,
    FLAG_RESPONSE,
    Frame,
    decode_frame,
    encode_frame,
    parse_hex,
)

PBKDF2_ITERATIONS = 10_000
DEFAULT_PASSWORD = "TestDpls01"
NOTIFY_PACE_MS = 80

HELLO = 0x01
AUTH_CHALLENGE = 0x02
AUTH_PROOF = 0x03
AUTH_RESULT = 0x04
SETUP = 0x05
DEVICE_INFO_GET = 0x06
DEVICE_INFO_REPORT = 0x07
NAME_SET = 0x08
PASSWORD_SET = 0x09
SETTINGS_RESULT = 0x0A
TIME_SYNC = 0x0B
STATE_GET = 0x10
STATE_REPORT = 0x11
MODE_SET = 0x12
COMMAND_RESULT = 0x13
IDENTIFY_START = 0x14
IDENTIFY_STOP = 0x15
LOG_START = 0x20
LOG_INFO = 0x21
LOG_CHUNK = 0x22
LOG_ACK = 0x23
LOG_RESULT = 0x25
LOG_HIST_GET = 0x26
LOG_HIST_REPORT = 0x27
KEEP_ALIVE = 0x30
ERROR = 0x7F

AUTH_PREFIX_TYPES = {
    DEVICE_INFO_GET,
    NAME_SET,
    PASSWORD_SET,
    TIME_SYNC,
    STATE_GET,
    MODE_SET,
    LOG_START,
    LOG_ACK,
    LOG_HIST_GET,
    KEEP_ALIVE,
}


@dataclass(frozen=True)
class TraceFrame:
    direction: str
    frame: Frame
    raw: bytes


@dataclass
class ReplaySession:
    session_id: int | None = None
    token: bytes | None = None
    challenge: Frame | None = None


class SimulatorProcess:
    def __init__(self, executable: Path, environment: str = "lab") -> None:
        self.proc = subprocess.Popen(
            [str(executable)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        assert self.proc.stdin is not None and self.proc.stdout is not None
        self.stdin = self.proc.stdin
        self.stdout = self.proc.stdout
        banner = self.stdout.readline().rstrip("\n")
        while banner and not banner.startswith("READY"):
            banner = self.stdout.readline().rstrip("\n")
        if not banner.startswith("READY"):
            self.close(force=True)
            raise RuntimeError(f"unexpected simulator banner: {banner!r}")
        self.command("CONNECT")
        self.command("ENCRYPT 1")
        self.command("CCCD 3")
        if environment == "lab":
            self.command("LAB")
        elif environment == "line":
            self.command("POWER LINE")

    def command(self, line: str) -> list[str]:
        self.stdin.write(line + "\n")
        self.stdin.flush()
        out: list[str] = []
        while True:
            got = self.stdout.readline()
            if got == "":
                raise RuntimeError(f"simulator closed stdout after {line!r}")
            text = got.rstrip("\n")
            if text == "DONE":
                return out
            out.append(text)

    @staticmethod
    def _decode_tx(lines: list[str]) -> list[Frame]:
        frames: list[Frame] = []
        for line in lines:
            if not line.startswith("TX "):
                continue
            encoded = parse_hex(line.removeprefix("TX ").strip())
            decoded = decode_frame(encoded) if encoded is not None else None
            if decoded is not None:
                frames.append(decoded)
        return frames

    def frame(self, raw: bytes) -> list[Frame]:
        # CCCD 0x03 uses the PHY6252 notify path. The host PHY model deliberately
        # keeps one TX in-flight until its 80 ms pacing tick, exactly like
        # SimulatorBleTransport. Advance that timer before the next request so a
        # replay cannot accidentally hide behind an uncleared previous response.
        frames = self._decode_tx(self.command(f"FRAME {raw.hex().upper()}"))
        frames.extend(self._decode_tx(self.command(f"TICK {NOTIFY_PACE_MS}")))
        return frames

    def close(self, force: bool = False) -> None:
        if self.proc.poll() is not None:
            return
        try:
            if not force:
                self.command("DISCONNECT")
                self.stdin.write("QUIT\n")
                self.stdin.flush()
                self.proc.wait(timeout=2)
                return
        except (BrokenPipeError, RuntimeError, subprocess.TimeoutExpired):
            pass
        self.proc.kill()
        try:
            self.proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            pass


def read_trace(path: Path) -> list[TraceFrame]:
    result: list[TraceFrame] = []
    for lineno, raw_line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if parts[0] not in {"TX", "RX"}:
            continue
        hex_text = parts[-1]
        raw = parse_hex(hex_text)
        frame = decode_frame(raw) if raw is not None else None
        if raw is None or frame is None:
            raise ValueError(f"{path}:{lineno}: invalid DPLS frame")
        result.append(TraceFrame(parts[0], frame, raw))
    if not any(item.direction == "TX" for item in result):
        raise ValueError(f"{path}: no TX frames")
    return result


def derive_verifier(password: str, salt: bytes) -> bytes:
    return hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt,
        PBKDF2_ITERATIONS,
        dklen=32,
    )


def auth_proof(password: str, challenge: Frame, client_nonce: bytes) -> tuple[int, bytes]:
    if challenge.msg_type != AUTH_CHALLENGE or len(challenge.payload) < 37:
        raise ValueError("simulator returned malformed AUTH_CHALLENGE")
    session_id = struct.unpack_from("<I", challenge.payload, 0)[0]
    device_nonce = challenge.payload[4:20]
    salt = challenge.payload[20:36]
    verifier = derive_verifier(password, salt)
    message = device_nonce + client_nonce + struct.pack("<I", session_id)
    proof = hmac.new(verifier, message, hashlib.sha256).digest()
    return session_id, proof


def transplant_request(frame: Frame, session: ReplaySession, password: str) -> bytes:
    payload = frame.payload
    if frame.msg_type == AUTH_PROOF:
        if session.challenge is None:
            raise ValueError("AUTH_PROOF appears before simulator AUTH_CHALLENGE")
        if len(payload) < 16:
            raise ValueError("captured AUTH_PROOF is too short")
        client_nonce = payload[:16]
        session_id, proof = auth_proof(password, session.challenge, client_nonce)
        session.session_id = session_id
        payload = client_nonce + proof
    elif frame.msg_type == SETUP:
        if session.session_id is None or len(payload) < 4:
            raise ValueError("SETUP appears before a simulator sessionId")
        payload = struct.pack("<I", session.session_id) + payload[4:]
    elif frame.msg_type in AUTH_PREFIX_TYPES and len(payload) >= 12:
        if session.session_id is None or session.token is None:
            raise ValueError(f"{frame.type_name} appears before authenticated simulator session")
        payload = struct.pack("<I", session.session_id) + session.token + payload[12:]
    return encode_frame(frame.msg_type, frame.sequence, payload, flags=frame.flags)


def response_signature(frame: Frame) -> tuple[object, ...]:
    flags = frame.flags & (FLAG_RESPONSE | FLAG_ERROR | FLAG_EVENT)
    payload = frame.payload
    prefix: tuple[object, ...] = (frame.msg_type, flags)

    if frame.msg_type == ERROR:
        return prefix + (payload[0] if payload else None,)
    if frame.msg_type == AUTH_CHALLENGE:
        return prefix + ((payload[36] if len(payload) >= 37 else None),)
    if frame.msg_type == AUTH_RESULT:
        status = payload[0] if payload else None
        retry = struct.unpack_from("<H", payload, 1)[0] if len(payload) >= 3 else None
        return prefix + (status, retry)
    if frame.msg_type == COMMAND_RESULT:
        return prefix + (
            payload[0] if len(payload) >= 1 else None,
            payload[1] if len(payload) >= 2 else None,
        )
    if frame.msg_type == SETTINGS_RESULT:
        return prefix + (payload[0] if payload else None,)
    if frame.msg_type == STATE_REPORT:
        mode = payload[0] if len(payload) >= 1 else None
        power = payload[1] if len(payload) >= 2 else None
        reserve_low = payload[6] if len(payload) >= 7 else None
        real_short = ((payload[7] >> 1) & 1) if len(payload) >= 8 else None
        return prefix + (mode, power, reserve_low, real_short)
    if frame.msg_type == DEVICE_INFO_REPORT:
        protocol = payload[4] if len(payload) >= 5 else None
        hw = payload[8] if len(payload) >= 9 else None
        capabilities = payload[9] if len(payload) >= 10 else None
        settings_state = payload[10] if len(payload) >= 11 else None
        return prefix + (protocol, hw, capabilities, settings_state)
    if frame.msg_type == LOG_INFO:
        count = struct.unpack_from("<H", payload, 8)[0] if len(payload) >= 10 else None
        return prefix + (count,)
    if frame.msg_type == LOG_CHUNK:
        first = struct.unpack_from("<H", payload, 0)[0] if len(payload) >= 2 else None
        count = payload[2] if len(payload) >= 3 else None
        return prefix + (first, count)
    if frame.msg_type == LOG_RESULT:
        return prefix + (payload[0] if payload else None,)
    if frame.msg_type in {TIME_SYNC, IDENTIFY_START, IDENTIFY_STOP}:
        return prefix
    if frame.msg_type == LOG_HIST_REPORT:
        return prefix + (len(payload),)
    return prefix + (len(payload),)


def signature_text(signature: tuple[object, ...]) -> str:
    return "/".join(str(item) for item in signature)


def replay(
    trace: list[TraceFrame],
    simulator: Path,
    password: str,
    environment: str,
    verbose: bool = False,
) -> tuple[int, int, list[str]]:
    expected: dict[int, list[Frame]] = {}
    for item in trace:
        if item.direction == "RX":
            expected.setdefault(item.frame.sequence, []).append(item.frame)

    sim = SimulatorProcess(simulator, environment=environment)
    session = ReplaySession()
    compared = 0
    mismatches: list[str] = []
    seen_expected: set[tuple[int, int]] = set()

    try:
        for request_item in (item for item in trace if item.direction == "TX"):
            request = request_item.frame
            try:
                transplanted = transplant_request(request, session, password)
            except ValueError as exc:
                mismatches.append(f"seq={request.sequence} {request.type_name}: {exc}")
                continue

            actual_frames = sim.frame(transplanted)
            for actual in actual_frames:
                if actual.msg_type == AUTH_CHALLENGE:
                    session.challenge = actual
                    if len(actual.payload) >= 4:
                        session.session_id = struct.unpack_from("<I", actual.payload, 0)[0]
                elif actual.msg_type == AUTH_RESULT and len(actual.payload) >= 11 and actual.payload[0] == 0:
                    session.token = actual.payload[3:11]

            wanted = expected.get(request.sequence, [])
            if not wanted and actual_frames:
                mismatches.append(
                    f"seq={request.sequence} {request.type_name}: simulator emitted "
                    f"{', '.join(frame.type_name for frame in actual_frames)} but capture has no RX",
                )
                continue
            if wanted and not actual_frames:
                mismatches.append(
                    f"seq={request.sequence} {request.type_name}: capture expects "
                    f"{', '.join(frame.type_name for frame in wanted)} but simulator emitted no response",
                )
                continue

            pairs = min(len(wanted), len(actual_frames))
            for index in range(pairs):
                captured = wanted[index]
                actual = actual_frames[index]
                seen_expected.add((request.sequence, index))
                compared += 1
                captured_sig = response_signature(captured)
                actual_sig = response_signature(actual)
                if verbose:
                    print(
                        f"seq={request.sequence:05d} {request.type_name:<18} "
                        f"capture={signature_text(captured_sig)} sim={signature_text(actual_sig)}",
                    )
                if captured_sig != actual_sig:
                    mismatches.append(
                        f"seq={request.sequence} {request.type_name}: "
                        f"capture {captured.type_name} [{signature_text(captured_sig)}] != "
                        f"sim {actual.type_name} [{signature_text(actual_sig)}]",
                    )
            if len(wanted) != len(actual_frames):
                mismatches.append(
                    f"seq={request.sequence} {request.type_name}: response count "
                    f"capture={len(wanted)} sim={len(actual_frames)}",
                )

        for sequence, frames in expected.items():
            for index, frame in enumerate(frames):
                if (sequence, index) not in seen_expected:
                    mismatches.append(
                        f"seq={sequence}: captured {frame.type_name} was never matched to a replayed request",
                    )
    finally:
        sim.close()

    expected_count = sum(len(frames) for frames in expected.values())
    return compared, expected_count, mismatches


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("frames_file", type=Path)
    parser.add_argument(
        "--simulator",
        type=Path,
        default=Path("firmware/build/dpls_simulator"),
    )
    parser.add_argument("--password", default=DEFAULT_PASSWORD)
    parser.add_argument(
        "--environment",
        choices=("lab", "line", "none"),
        default="lab",
        help="initial simulated electrical environment before replay",
    )
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    if not args.simulator.is_file():
        print(f"simulator not found: {args.simulator}", file=sys.stderr)
        return 2
    try:
        trace = read_trace(args.frames_file)
        compared, expected_count, mismatches = replay(
            trace,
            args.simulator,
            args.password,
            args.environment,
            verbose=args.verbose,
        )
    except (OSError, RuntimeError, ValueError) as exc:
        print(f"differential replay failed: {exc}", file=sys.stderr)
        return 2

    print(
        f"differential_replay compared={compared} captured_rx={expected_count} "
        f"mismatches={len(mismatches)}",
    )
    for mismatch in mismatches:
        print(f"MISMATCH {mismatch}")
    return 1 if mismatches else 0


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    raise SystemExit(main())
