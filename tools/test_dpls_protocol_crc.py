#!/usr/bin/env python3
"""Cross-language DPLS wire and simulator-parity contract checks.

`protocol/dpls-wire.json` is the machine-readable canonical contract. C, Kotlin
and the dependency-free Python capture codec intentionally remain independent
implementations, but CI fails if their public constants drift. The same gate
also keeps the host simulator from inventing hardware/protocol semantics that
are absent on the PHY6252 target.
"""
from __future__ import annotations

import json
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "protocol/dpls-wire.json"

failures: list[str] = []


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def fail(message: str) -> None:
    failures.append(message)


def require(source: str, needle: str, label: str) -> None:
    if needle not in source:
        fail(label)


def forbid(source: str, needle: str, label: str) -> None:
    if needle in source:
        fail(label)


def bit_index(value: int) -> int:
    if value <= 0 or value & (value - 1):
        raise ValueError(f"flag must be one bit, got {value}")
    return value.bit_length() - 1


def crc16_ccitt_false(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if crc & 0x8000 else crc << 1
            crc &= 0xFFFF
    return crc


def wire_known_answer(contract: dict[str, object]) -> None:
    version = int(contract["wire_version"])
    messages = contract["messages"]
    flags = contract["flags"]
    assert isinstance(messages, dict) and isinstance(flags, dict)
    payload = bytes.fromhex("1122334455")
    body = (
        bytes([version, int(messages["MODE_SET"]), int(flags["REQUEST"])])
        + struct.pack("<HH", 0x1234, len(payload))
        + payload
    )
    frame = body + struct.pack("<H", crc16_ccitt_false(body))
    if frame.hex().upper() != "0212013412050011223344550098":
        fail("wire known-answer changed unexpectedly")


def check_wire(contract: dict[str, object]) -> None:
    version = int(contract["wire_version"])
    frame = contract["frame"]
    flags = contract["flags"]
    messages = contract["messages"]
    assert isinstance(frame, dict) and isinstance(flags, dict) and isinstance(messages, dict)

    c = text("firmware/include/dpls_protocol.h")
    require(c, f"#define DPLS_PROTOCOL_VERSION {version}u", "C wire version drift")
    require(c, f"#define DPLS_PROTOCOL_OVERHEAD {int(frame['overhead'])}u", "C frame overhead drift")
    require(c, f"#define DPLS_MAX_PAYLOAD {int(frame['max_payload'])}u", "C max payload drift")
    for name, value in flags.items():
        require(c, f"DPLS_FLAG_{name}", f"C missing flag {name}")
        require(c, f"(1u << {bit_index(int(value))})", f"C flag {name} drift")
    for name, value in messages.items():
        require(c, f"DPLS_MSG_{name} = 0x{int(value):02x}", f"C message {name} drift")

    kt = text("mobile/wire/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsProtocol.kt")
    require(kt, f"const val VERSION: Byte = {version}", "Kotlin wire version drift")
    require(kt, f"const val HEADER_SIZE = {int(frame['header_size'])}", "Kotlin header size drift")
    require(kt, f"const val TRAILER_SIZE = {int(frame['trailer_size'])}", "Kotlin trailer size drift")
    require(kt, f"const val MAX_PAYLOAD = {int(frame['max_payload'])}", "Kotlin max payload drift")
    for name, value in flags.items():
        require(kt, f"const val {name} = 1 shl {bit_index(int(value))}", f"Kotlin flag {name} drift")
    for name, value in messages.items():
        require(kt, f"{name}(0x{int(value):02x})", f"Kotlin message {name} drift")

    py = text("tools/session_capture/dpls_wire.py")
    require(py, f"VERSION = {version}", "Python wire version drift")
    require(py, f"HEADER_SIZE = {int(frame['header_size'])}", "Python header size drift")
    require(py, f"TRAILER_SIZE = {int(frame['trailer_size'])}", "Python trailer size drift")
    for name, value in flags.items():
        require(py, f"FLAG_{name} = 1 << {bit_index(int(value))}", f"Python flag {name} drift")
    for name, value in messages.items():
        require(py, f"0x{int(value):02X}: \"{name}\"", f"Python message {name} drift")

    # The TypeScript lab is transport/UI around the real KMP phone and C simulator.
    # It must never grow a fourth DPLS frame/message implementation under another filename.
    lab_root = ROOT / "tools/dpls-lab"
    message_names = "|".join(re.escape(name) for name in messages)
    hex_table = re.compile(rf"\b(?:{message_names})\s*:\s*0x[0-9a-f]+", re.IGNORECASE)
    for path in list(lab_root.rglob("*.ts")) + list(lab_root.rglob("*.tsx")):
        source = path.read_text(encoding="utf-8")
        if hex_table.search(source):
            fail(f"{path.relative_to(ROOT)} contains a duplicate DPLS message table")
        for symbol in ("PROTOCOL_VERSION", "FLAG_REQUEST", "FLAG_RESPONSE"):
            if re.search(rf"\b{symbol}\b\s*=", source):
                fail(f"{path.relative_to(ROOT)} contains duplicate wire constant {symbol}")

    wire_known_answer(contract)


def check_advertisement(contract: dict[str, object]) -> None:
    adv = contract["advertisement"]
    assert isinstance(adv, dict)
    bits = adv["status_bits"]
    assert isinstance(bits, dict)
    kt = text("mobile/wire/src/commonMain/kotlin/ru/bolid/testdpls/core/protocol/DplsAdvertisement.kt")
    require(kt, f"const val COMPANY_ID = 0x{int(adv['company_id']):04X}", "Kotlin company id drift")
    for name, value in bits.items():
        require(kt, f"const val {name} = 0x{int(value):02X}", f"Kotlin ADV bit {name} drift")

    if not bool(adv["target_emits_dynamic_status"]):
        target = text("firmware/targets/phy6252/source/dplsBLEPeripheral.c")
        require(
            target,
            "0x01, 0x0b,\n    0x00,\n    DPLS_FW_VERSION_MAJOR",
            "PHY6252 ADV status is no longer reserved-zero; update protocol contract intentionally",
        )
        hub = text("tools/dpls-lab/hub.ts")
        require(hub, "function advStatusFromBoard(_board: BoardSnapshot): number {", "lab ADV parity helper missing")
        require(hub, "return 0; // target scan response reserves the status byte but currently emits zero", "simulator advertises richer status than PHY6252")
        for richer in ("board.real_short", "board.power", "board.reserve_low"):
            forbid(hub, richer + " === 1", f"simulator ADV derives unsupported target state: {richer}")


def check_modes_and_pins(contract: dict[str, object]) -> None:
    modes = contract["modes"]
    assert isinstance(modes, dict)
    safety = text("firmware/include/dpls_safety.h")
    target = text("firmware/phy6252/dpls_phy6252_app.c")
    sim = text("firmware/sim/dpls_sim_board.c")
    board = text("firmware/phy6252/dpls_board.h")
    pins = text("tools/dpls-lab/src/pins.ts")

    for name, spec in modes.items():
        assert isinstance(spec, dict)
        wire = int(spec["wire"])
        require(safety, f"DPLS_SAFE_{name} = {wire}", f"firmware mode value {name} drift")
        output = spec["output"]
        if output is None:
            continue
        output = str(output)
        require(
            target,
            f"case DPLS_MODE_{name}:\n        hal_gpio_write(DPLS_PIN_{output}, 1);",
            f"PHY6252 mode {name} no longer drives {output}",
        )
        sim_field = output.lower()
        require(
            sim,
            f"case DPLS_MODE_{name}:\n        board->gpio_{sim_field} = true;",
            f"simulator mode {name} no longer mirrors {output}",
        )
        pin_match = re.search(rf"#define DPLS_PIN_{re.escape(output)} GPIO_(P\d+)", board)
        if pin_match is None:
            fail(f"board mapping missing DPLS_PIN_{output}")
            continue
        pin = pin_match.group(1)
        if not re.search(rf'id: "{pin}"[^\n]+role: "{re.escape(output)}"', pins):
            fail(f"lab pin model does not map {pin} to {output}")


def main() -> int:
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    check_wire(contract)
    check_advertisement(contract)
    check_modes_and_pins(contract)
    if failures:
        print("DPLS contract guard failed:")
        for item in failures:
            print(f"  - {item}")
        return 1
    print("OK: DPLS wire contract matches C/Kotlin/Python")
    print("OK: lab contains no second DPLS protocol table")
    print("OK: simulator ADV semantics match current PHY6252 target")
    print("OK: mode/output/pin mappings agree across target, simulator and lab")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
