#!/usr/bin/env python3
"""Parse a recorded session log into NDJSON events + simulator-oriented frames.

Input: multiplexed text from record_session.py
Output: <stem>.ndjson and <stem>.frames.txt (TX phone→device / RX device→phone)
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from dpls_wire import decode_frame, parse_hex

LOGCAT_MSG = re.compile(
    r"^(?P<mon>\d{2}-\d{2})\s+(?P<hms>\d{2}:\d{2}:\d{2}\.\d{3})\s+"
    r"(?:(?P<pid>\d+)\s+(?P<tid>\d+)\s+)?"
    r"(?P<level>[VDIWEF])\s*"
    r"(?:/)?(?P<tag>[^:(]+)(?:\([^)]*\))?:\s+(?P<msg>.*)$",
)
HEX_RX = re.compile(r"RX indication bytes=(?P<n>\d+)\s+hex=(?P<hex>[0-9A-Fa-f]+)")
HEX_TX = re.compile(r"TX write bytes=(?P<n>\d+)\s+hex=(?P<hex>[0-9A-Fa-f]+)")
STATE = re.compile(r"^STATE\s+(?P<body>.+)$")
ROW = re.compile(r"^(?P<ts>\S+)\t(?P<source>[^\t]+)\t(?P<body>.*)$")


def emit(events: list[dict[str, object]], event: dict[str, object]) -> None:
    events.append(event)


def handle_message(ts: str, source: str, tag: str, msg: str, events: list[dict[str, object]]) -> None:
    base: dict[str, object] = {"ts": ts, "source": source, "tag": tag, "raw": msg}

    rx = HEX_RX.search(msg)
    if rx:
        raw = parse_hex(rx.group("hex"))
        frame = decode_frame(raw) if raw is not None else None
        event = {
            **base,
            "kind": "ble_rx",
            "bytes": int(rx.group("n")),
            "hex": rx.group("hex").upper(),
        }
        if frame is not None:
            event.update(
                {
                    "type": frame.type_name,
                    "type_wire": frame.msg_type,
                    "sequence": frame.sequence,
                    "flags": frame.flags,
                    "payload_len": len(frame.payload),
                },
            )
        emit(events, event)
        return

    tx = HEX_TX.search(msg)
    if tx:
        raw = parse_hex(tx.group("hex"))
        frame = decode_frame(raw) if raw is not None else None
        event = {
            **base,
            "kind": "ble_tx",
            "bytes": int(tx.group("n")),
            "hex": tx.group("hex").upper(),
        }
        if frame is not None:
            event.update(
                {
                    "type": frame.type_name,
                    "type_wire": frame.msg_type,
                    "sequence": frame.sequence,
                    "flags": frame.flags,
                    "payload_len": len(frame.payload),
                },
            )
        emit(events, event)
        return

    state = STATE.match(msg)
    if state:
        fields: dict[str, object] = {}
        for part in state.group("body").split():
            if "=" in part:
                key, value = part.split("=", 1)
                fields[key] = value
        emit(events, {**base, "kind": "state", "fields": fields})
        return

    if msg.startswith("E2E ") or msg.startswith("LOG_"):
        emit(events, {**base, "kind": "client", "message": msg})
        return

    if msg.startswith(("connectGatt", "gatt state=", "MTU changed", "Services discovered", "CCCD written")):
        emit(events, {**base, "kind": "lifecycle", "message": msg})
        return

    if source == "uart":
        emit(events, {**base, "kind": "uart", "message": msg})
        return

    emit(events, {**base, "kind": "other", "message": msg})


def parse_log(path: Path) -> list[dict[str, object]]:
    events: list[dict[str, object]] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        row = ROW.match(line)
        if not row:
            continue
        ts = row.group("ts")
        source = row.group("source")
        body = row.group("body")
        if source == "meta":
            emit(events, {"ts": ts, "source": source, "kind": "meta", "message": body})
            continue
        if source == "uart":
            handle_message(ts, source, "uart", body, events)
            continue
        match = LOGCAT_MSG.match(body)
        if match:
            handle_message(ts, source, match.group("tag").strip(), match.group("msg"), events)
        else:
            # Already stripped tag forms from TestDplsSession stderr / soft-BLE
            tag = "raw"
            msg = body
            if ": " in body:
                maybe_tag, maybe_msg = body.split(": ", 1)
                if " " not in maybe_tag and len(maybe_tag) < 40:
                    tag, msg = maybe_tag, maybe_msg
            handle_message(ts, source, tag, msg, events)
    return events


def write_frames(events: list[dict[str, object]], path: Path) -> int:
    count = 0
    with path.open("w", encoding="utf-8") as fh:
        for event in events:
            kind = event.get("kind")
            hex_payload = event.get("hex")
            if kind not in {"ble_tx", "ble_rx"} or not isinstance(hex_payload, str):
                continue
            direction = "TX" if kind == "ble_tx" else "RX"
            type_name = event.get("type", "?")
            fh.write(f"{direction} {type_name} {hex_payload}\n")
            count += 1
    return count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session_log", type=Path)
    parser.add_argument("--out-dir", type=Path, default=None)
    args = parser.parse_args()
    log_path = args.session_log
    out_dir = args.out_dir or log_path.parent
    out_dir.mkdir(parents=True, exist_ok=True)
    events = parse_log(log_path)
    ndjson_path = out_dir / (log_path.stem + ".ndjson")
    frames_path = out_dir / (log_path.stem + ".frames.txt")
    with ndjson_path.open("w", encoding="utf-8") as fh:
        for event in events:
            fh.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")
    frame_count = write_frames(events, frames_path)
    kinds: dict[str, int] = {}
    for event in events:
        kind = str(event.get("kind", "other"))
        kinds[kind] = kinds.get(kind, 0) + 1
    summary = ", ".join(f"{k}={v}" for k, v in sorted(kinds.items()))
    print(f"events={len(events)} frames={frame_count} ({summary})")
    print(f"wrote {ndjson_path}")
    print(f"wrote {frames_path}")
    return 0


if __name__ == "__main__":
    # Allow `python3 tools/session_capture/parse_session.py` without PYTHONPATH.
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    raise SystemExit(main())
