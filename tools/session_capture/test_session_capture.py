#!/usr/bin/env python3
"""Offline smoke tests for session_capture wire/parse helpers (no phone/board)."""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from dpls_wire import FLAG_REQUEST, FLAG_RESPONSE, decode_frame, encode_frame  # noqa: E402
from parse_session import parse_log, write_frames  # noqa: E402


def test_roundtrip_frame() -> None:
    raw = encode_frame(0x10, 7, bytes([1, 2, 3, 4]), flags=FLAG_REQUEST)
    frame = decode_frame(raw)
    assert frame is not None
    assert frame.msg_type == 0x10
    assert frame.sequence == 7
    assert frame.payload == bytes([1, 2, 3, 4])


def test_parse_sample_session() -> None:
    hello = encode_frame(0x01, 1, bytes([0] * 8), flags=FLAG_REQUEST)
    challenge = encode_frame(0x02, 1, bytes([0] * 40), flags=FLAG_RESPONSE)
    sample = "\n".join(
        [
            "2026-08-16T13:00:00.000Z\tmeta\tsession_start",
            "2026-08-16T13:00:00.010Z\tlogcat\t08-16 13:00:00.010  1  1 I TestDplsBle: connectGatt AA:BB",
            f"2026-08-16T13:00:00.020Z\tlogcat\t08-16 13:00:00.020  1  1 I TestDplsBle: TX write bytes={len(hello)} hex={hello.hex().upper()}",
            f"2026-08-16T13:00:00.030Z\tlogcat\t08-16 13:00:00.030  1  1 I TestDplsBle: RX indication bytes={len(challenge)} hex={challenge.hex().upper()}",
            "2026-08-16T13:00:00.040Z\tlogcat\t08-16 13:00:00.040  1  1 I TestDplsSession: E2E login ready",
            "2026-08-16T13:00:00.050Z\tlogcat\t08-16 13:00:00.050  1  1 I TestDplsSession: STATE mode=0 power=DPLS reserve_low=0 real_short=0 line_mv=12000 port1_mv=12000 port2_mv=12000 port_t_mv=12000 reserve_mv=5000",
            "2026-08-16T13:00:01.000Z\tmeta\tsession_end",
            "",
        ],
    )
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "sample.log"
        path.write_text(sample, encoding="utf-8")
        events = parse_log(path)
        kinds = {event["kind"] for event in events}
        assert "ble_tx" in kinds
        assert "ble_rx" in kinds
        assert "client" in kinds
        assert "state" in kinds
        frames_path = Path(tmp) / "sample.frames.txt"
        count = write_frames(events, frames_path)
        assert count == 2
        text = frames_path.read_text(encoding="utf-8")
        assert "TX HELLO" in text
        assert "RX AUTH_CHALLENGE" in text
        state_events = [event for event in events if event.get("kind") == "state"]
        assert state_events
        fields = state_events[0]["fields"]
        assert isinstance(fields, dict)
        assert fields.get("mode") == "0"
        assert fields.get("line_mv") == "12000"


def main() -> int:
    test_roundtrip_frame()
    test_parse_sample_session()
    print("OK: session_capture smoke")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
