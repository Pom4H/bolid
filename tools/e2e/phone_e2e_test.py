#!/usr/bin/env python3
"""End-to-end Test-DPLS walkthrough via ADB.

Phases:
  СТАРТ  — первичная настройка или вход; 3 неверных пароля (блокировка на FW: 5)
  ОСНОВНОЙ — испытания, журнал (+ --fill-journal для ротации 200), экспорт, имя/пароль

Flags:
  --fast          быстрый прогон (без наполнения журнала до 200)
  --fill-journal  наполнить журнал до 200 и проверить ротацию
  --start-only    только фаза СТАРТ
  --main-only     только фаза ОСНОВНОЙ (устройство уже в сессии)
  --connect-only  alias для --start-only
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import Iterable

ADB = os.environ.get("ADB") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")

def _adb_device() -> str:
    out = subprocess.check_output([ADB, "devices", "-l"], text=True)
    for line in out.splitlines():
        if "device product:" in line:
            return line.split()[0]
    return "192.168.88.247:46329"


DEV = _adb_device()
PKG = "com.thebutton.ble"
ACTIVITY = f"{PKG}/.MainActivity"
PASSWORD = "TestDpls01"
WRONG_PASSWORD = "WrongPwd1"
DEVICE_NAME = "Test-DPLS-E2E"
DEVICE_NAME_NEW = "Test-DPLS-Renamed"
PASSWORD_NEW = "TestDpls02"
JOURNAL_CAPACITY = 200
LOCKOUT_ATTEMPTS = 3
AUTH_BLOCK_ATTEMPTS_FW = 5
TEST_MODES = ("КЗ +1", "КЗ +2", "КЗ +Т", "Обрыв +Т", "Обрыв магистрали")
MODE_WIRE = {
    "КЗ +1": 3,
    "КЗ +2": 4,
    "КЗ +Т": 5,
    "Обрыв +Т": 1,
    "Обрыв магистрали": 2,
}
MAC_RE = re.compile(r"([0-9A-F]{2}(?::[0-9A-F]{2}){5})", re.I)
DPLS_BOND_NAME = re.compile(r"Test-DPLS|DPLS", re.I)
BONDED_DPLS_RE = re.compile(
    r"([0-9A-F]{2}(?::[0-9A-F]{2}){5})\s+\[\s*LE\s*\]\s+(\S+)",
    re.I,
)

LOG_TS = re.compile(
    r"^(?P<mon>\d{2}-\d{2})\s+(?P<hms>\d{2}:\d{2}:\d{2}\.\d{3}).*TestDplsBle:\s+(?P<msg>.*)$"
)

POLL = 0.03
FAST = "--fast" in sys.argv
CONNECT_ONLY = "--connect-only" in sys.argv
START_ONLY = "--start-only" in sys.argv
MAIN_ONLY = "--main-only" in sys.argv
FILL_JOURNAL = "--fill-journal" in sys.argv
JOURNAL_ONLY = "--journal-only" in sys.argv
NO_JOURNAL = "--no-journal" in sys.argv
KEEP_BOND = "--keep-bond" in sys.argv or JOURNAL_ONLY
if JOURNAL_ONLY:
    FILL_JOURNAL = True
IDENTIFY_LED_OBSERVE_SEC = 0.0
if CONNECT_ONLY:
    FAST = True
    START_ONLY = True


@dataclass
class Timer:
    t0: float = field(default_factory=time.perf_counter)
    marks: list[tuple[str, float]] = field(default_factory=list)

    def mark(self, name: str) -> float:
        elapsed = time.perf_counter() - self.t0
        self.marks.append((name, elapsed))
        log(f"  ⏱ {name}: {elapsed:.2f}s")
        return elapsed

    def delta(self, start: str, end: str) -> float | None:
        times = {name: value for name, value in self.marks}
        if start not in times or end not in times:
            return None
        return times[end] - times[start]

    def report(self) -> None:
        log("\n=== CONNECTION TIMING (wall clock) ===")
        for name, value in self.marks:
            log(f"  {value:7.2f}s  {name}")
        pairs = (
            ("scan_to_device", "app_ready", "device_found"),
            ("identify_gatt", "identify_tap", "identify_active"),
            ("auth_flow", "connect_tap", "test_screen_ready"),
            ("total_connect", "connect_tap", "test_screen_ready"),
            ("total_cold_start", "app_ready", "test_screen_ready"),
        )
        log("\n=== KEY DELTAS ===")
        for label, start, end in pairs:
            delta = self.delta(start, end)
            if delta is not None:
                log(f"  {label}: {delta:.2f}s ({start} → {end})")


def parse_ble_timeline(log_text: str) -> list[tuple[float, str]]:
    events: list[tuple[float, str]] = []
    for line in log_text.splitlines():
        match = LOG_TS.match(line.strip())
        if not match:
            continue
        h, m, s = match.group("hms").split(":")
        sec = int(h) * 3600 + int(m) * 60 + float(s)
        events.append((sec, match.group("msg").strip()))
    if not events:
        return []
    base = events[0][0]
    return [(ts - base, msg) for ts, msg in events]


def report_ble_timeline(log_text: str, connect_tap_at: float | None = None) -> None:
    events = parse_ble_timeline(log_text)
    if not events:
        log("\n=== BLE LOG TIMELINE ===\n  (no TestDplsBle events)")
        return
    log("\n=== BLE LOG TIMELINE (from logcat) ===")
    interesting = (
        "connectGatt",
        "Connection state",
        "MTU changed",
        "Services discovered",
        "CCCD written",
        "RX indication",
        "TX write status",
    )
    for offset, msg in events:
        if any(token in msg for token in interesting):
            log(f"  {offset:7.3f}s  {msg}")
    # Find connect-after-confirm sequence
    connect_idx = next((i for i, (_, msg) in enumerate(events) if "connectGatt" in msg), None)
    if connect_idx is not None:
        seq = events[connect_idx:]
        milestones: list[tuple[str, float]] = []
        for offset, msg in seq:
            if "connectGatt" in msg and not any(m[0] == "connectGatt" for m in milestones):
                milestones.append(("connectGatt", offset))
            elif "Connection state" in msg and "state=2" in msg:
                milestones.append(("GATT connected", offset))
            elif "MTU changed" in msg and "status=0" in msg:
                milestones.append(("MTU OK", offset))
            elif "Services discovered" in msg and "status=0" in msg:
                milestones.append(("services OK", offset))
            elif "CCCD written" in msg and "status=0" in msg:
                milestones.append(("notify subscribed", offset))
            elif "RX indication bytes=46" in msg:
                milestones.append(("AUTH_CHALLENGE", offset))
            elif "RX indication bytes=25" in msg:
                milestones.append(("AUTH_RESULT", offset))
            elif "RX indication bytes=20" in msg or "RX indication bytes=21" in msg:
                if not any(m[0] == "STATE_REPORT" for m in milestones):
                    milestones.append(("STATE_REPORT", offset))
        if milestones:
            log("\n=== BLE CONNECT BREAKDOWN (logcat, from connectGatt) ===")
            t0 = milestones[0][1]
            for name, ts in milestones:
                log(f"  {ts - t0:7.3f}s  {name}")
            log(f"  total GATT→ready: {milestones[-1][1] - t0:.3f}s")


@dataclass
class Node:
    text: str
    desc: str
    clazz: str
    bounds: tuple[int, int, int, int]
    clickable: bool
    enabled: bool

    @property
    def label(self) -> str:
        return self.text or self.desc

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


def adb(*args: str, check: bool = True) -> str:
    cmd = [ADB, "-s", DEV, *args]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb failed: {' '.join(cmd)}\n{result.stderr}")
    return result.stdout + result.stderr


def log(msg: str) -> None:
    print(msg, flush=True)


def parse_bounds(raw: str) -> tuple[int, int, int, int]:
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw)
    if not m:
        return 0, 0, 0, 0
    return tuple(int(v) for v in m.groups())  # type: ignore[return-value]


def dump_nodes(retries: int | None = None) -> list[Node]:
    if retries is None:
        retries = 1 if FAST else 2
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
            xml = adb("shell", "cat", "/sdcard/ui.xml")
            if "<?xml" not in xml:
                raise RuntimeError("empty UI dump")
            root = ET.fromstring(xml)
            nodes: list[Node] = []
            for el in root.iter("node"):
                nodes.append(
                    Node(
                        text=el.attrib.get("text", ""),
                        desc=el.attrib.get("content-desc", ""),
                        clazz=el.attrib.get("class", ""),
                        bounds=parse_bounds(el.attrib.get("bounds", "")),
                        clickable=el.attrib.get("clickable", "false") == "true",
                        enabled=el.attrib.get("enabled", "true") == "true",
                    )
                )
            return nodes
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            time.sleep(0.03)
    raise RuntimeError(f"uiautomator dump failed after {retries} tries: {last_error}")


def e2e_broadcast(action: str, **extras: str | int) -> None:
    args = ["shell", "am", "broadcast", "-a", action]
    for key, value in extras.items():
        flag = "--ei" if isinstance(value, int) else "--es"
        args.extend([flag, key, str(value)])
    args.append(PKG)
    adb(*args, check=False)


def wait_logcat(substring: str, timeout: float = 30.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if substring in logcat_full():
            return
        time.sleep(0.06)
    raise TimeoutError(f"Logcat timeout waiting for {substring!r}")


def wait_logcat_any(substrings: tuple[str, ...], timeout: float = 30.0) -> str:
    deadline = time.time() + timeout
    while time.time() < deadline:
        log = logcat_full()
        for substring in substrings:
            if substring in log:
                return substring
        time.sleep(0.06)
    raise TimeoutError(f"Logcat timeout waiting for any of {substrings!r}")


def wait_identify_led(max_connect: float = 20.0, observe_sec: float | None = None) -> None:
    if observe_sec is None:
        observe_sec = IDENTIFY_LED_OBSERVE_SEC
    deadline = time.time() + max_connect
    led_at: float | None = None
    auth_submitted = False
    while time.time() < deadline:
        poll_pairing_dialog(duration=0)
        log = logcat_full()
        if not auth_submitted and (
            "E2E login ready" in log or "RX indication bytes=46" in log
        ) and "E2E setup ready" not in log:
            submit_password(PASSWORD)
            auth_submitted = True
        if "E2E identify led live" in log:
            if led_at is None:
                led_at = time.time()
            if time.time() - led_at >= observe_sec:
                return
        time.sleep(0.06)
    raise TimeoutError("identify LED not live within timeout")


def phase_banner(title: str) -> None:
    log(f"\n{'=' * 60}")
    log(f"  {title}")
    log(f"{'=' * 60}")


def submit_password(password: str) -> None:
    e2e_broadcast("com.thebutton.ble.E2E_FILL_LOGIN", password=password)


def confirm_identified_device() -> None:
    e2e_broadcast("com.thebutton.ble.E2E_CONFIRM")
    tap_text("Это устройство", clickable=None)


def reset_bluetooth_adapter() -> None:
    adb("shell", "svc", "bluetooth", "disable", check=False)
    time.sleep(1.0)
    adb("shell", "svc", "bluetooth", "enable", check=False)
    time.sleep(2.0)


def wait_credential_prompt(timeout: float = 45.0) -> str:
    kind = wait_logcat_any(("E2E setup ready", "E2E login ready"), timeout=timeout)
    return "setup" if kind == "E2E setup ready" else "login"


def run_setup_if_needed() -> str:
    if "E2E auth done" in logcat_full():
        return "login"
    kind = wait_logcat_any(("E2E setup ready", "E2E login ready", "E2E auth done"), timeout=45)
    if kind == "E2E auth done":
        return "login"
    if kind == "E2E setup ready":
        fill_setup_form()
        kind = wait_logcat_any(("E2E login ready", "E2E auth done"), timeout=90)
        if kind == "E2E login ready":
            return "login"
    return "login" if kind == "E2E login ready" else kind


def restart_app_for_fresh_login() -> None:
    """Настройка завершается авто-входом по verifier, закэшированному в
    процессе приложения, поэтому экран входа не появляется. Перезапускаем
    приложение и подключаемся заново без авто-подстановки пароля."""
    log("  lockout: перезапуск приложения для чистого экрана входа")
    clear_logcat()
    prepare_app(clear_data=False)
    wait_text("Устройства рядом", timeout=20)
    wait_for_device("Test-DPLS")
    tap_device("Test-DPLS")
    wait_identify_led(max_connect=25.0)
    confirm_identified_device()


def test_lockout_wrong_passwords(attempts: int = LOCKOUT_ATTEMPTS) -> None:
    if "E2E auth done" in logcat_full():
        restart_app_for_fresh_login()
    wait_logcat("E2E login ready", timeout=30)
    for attempt in range(1, attempts + 1):
        submit_password(WRONG_PASSWORD)
        wait_logcat("E2E auth wrong", timeout=12)
        log(f"  wrong password {attempt}/{attempts}: rejected")
    if "E2E auth blocked" in logcat_full():
        raise AssertionError(
            f"Blocked after {attempts} wrong passwords; firmware threshold is {AUTH_BLOCK_ATTEMPTS_FW}",
        )
    log(f"  lockout: {attempts} wrong passwords, not blocked (FW blocks at {AUTH_BLOCK_ATTEMPTS_FW})")


def complete_login(timeout: float = 60.0) -> None:
    deadline = time.time() + timeout
    auth_challenges = 0
    while time.time() < deadline:
        poll_pairing_dialog(duration=0)
        log = logcat_full()
        if "E2E auth done" in log:
            return
        if "связк" in log.lower() or "Ключи BLE устарели" in log:
            raise RuntimeError("BLE bond desync; retry pairing or factory-reset device")
        challenges = log.count("RX indication bytes=46")
        if challenges > auth_challenges:
            auth_challenges = challenges
            submit_password(PASSWORD)
        time.sleep(0.05)
    raise TimeoutError("Login timeout waiting for E2E auth done")


def quick_submit_login_after_confirm(timeout: float = 8.0) -> None:
    deadline = time.time() + timeout
    seen = logcat_full().count("RX indication bytes=46")
    while time.time() < deadline:
        log = logcat_full()
        if "E2E setup ready" in log:
            return
        if "E2E auth done" in log:
            return
        if log.count("RX indication bytes=46") > seen or "E2E login ready" in log:
            submit_password(PASSWORD)
            return
        time.sleep(0.05)


def connect_and_pair() -> None:
    step("Подключение: scan")
    wait_text("Устройства рядом", timeout=15)
    texts = wait_for_device("Test-DPLS")
    log("UI: " + " | ".join(t for t in texts if t))
    assert_no_ff_address(texts)

    step("Подключение: identify")
    tap_device("Test-DPLS")
    wait_identify_led(max_connect=25.0)

    step("Подключение: confirm device")
    confirm_identified_device()
    quick_submit_login_after_confirm()


def phase_start(results: list[tuple[str, str]], timer: Timer) -> None:
    phase_banner("СТАРТ: настройка / вход / блокировка")
    connect_and_pair()
    timer.mark("connect_tap")

    step("Старт: первичная настройка или вход")
    prompt = run_setup_if_needed()

    if JOURNAL_ONLY:
        step("Старт: вход (lockout пропущен)")
        results.append(("setup", "SKIP" if prompt == "login" else "OK"))
        results.append(("lockout_3_wrong", "SKIP"))
        complete_login(timeout=60)
    else:
        if prompt == "login":
            results.append(("setup", "SKIP"))
        else:
            results.append(("setup", "OK"))
        step(f"Старт: {LOCKOUT_ATTEMPTS} неверных пароля")
        test_lockout_wrong_passwords(LOCKOUT_ATTEMPTS)
        results.append(("lockout_3_wrong", "OK"))
        step("Старт: вход по паролю")
        complete_login(timeout=60)

    timer.mark("test_screen_ready")
    texts = ensure_test_tab()
    log("After start auth: " + " | ".join(t for t in texts if t))
    results.append(("login", "OK"))


def journal_stats_from_logcat() -> tuple[int, int | None, int | None]:
    logcat = logcat_full()
    info = re.search(r"LOG_INFO events=(\d+)", logcat)
    ready = re.search(r"E2E journal ready records=(\d+) seq_min=(\d+) seq_max=(\d+)", logcat)
    if ready:
        return int(ready.group(1)), int(ready.group(2)), int(ready.group(3))
    if info:
        return int(info.group(1)), None, None
    done = re.search(r"LOG_DONE raw=\d+ records=(\d+)", logcat)
    if done:
        return int(done.group(1)), None, None
    return 0, None, None


def reload_journal() -> tuple[int, int | None, int | None]:
    tap_bottom_nav("Испытание")
    time.sleep(0.1)
    tap_bottom_nav("Журнал")
    deadline = time.time() + 300.0
    while time.time() < deadline:
        texts = visible_texts(dump_nodes())
        joined = "\n".join(texts)
        logcat = logcat_full()
        if "E2E journal ready" in logcat or "LOG_DONE" in logcat:
            stats = journal_stats_from_logcat()
            ensure_test_tab(timeout=8)
            return stats
        if "Режим:" in joined and "Загрузка журнала" not in joined:
            match = re.search(r"E2E journal ready records=(\d+)", logcat)
            if match:
                stats = journal_stats_from_logcat()
                ensure_test_tab(timeout=8)
                return stats
            if any("Режим:" in t for t in texts):
                ensure_test_tab(timeout=8)
                return len([t for t in texts if "Режим:" in t]), None, None
        if "Не удалось загрузить" in joined:
            tap_text("Повторить", clickable=None)
        time.sleep(0.08)
    raise TimeoutError("Journal load timeout")


def wait_journal_idle(timeout: float = 15.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        logcat = logcat_full()
        if "LOG_INFO events=" in logcat and "LOG_DONE" not in logcat and "E2E journal ready" not in logcat:
            time.sleep(0.1)
            continue
        return
    raise TimeoutError("Journal transfer did not finish")


def probe_log_events(timeout: float = 300.0) -> int:
    clear_logcat()
    e2e_broadcast("com.thebutton.ble.E2E_LOAD_JOURNAL")
    deadline = time.time() + timeout
    while time.time() < deadline:
        logcat = logcat_full()
        match = re.search(r"LOG_INFO events=(\d+)", logcat)
        if match and ("LOG_DONE" in logcat or "E2E journal ready" in logcat):
            return int(match.group(1))
        if "LOG_DONE" in logcat or "E2E journal ready" in logcat:
            records, _, _ = journal_stats_from_logcat()
            if records > 0:
                return records
            if match:
                return int(match.group(1))
        time.sleep(0.08)
    raise TimeoutError("Journal probe timeout (LOG_INFO)")


def fill_journal_to_capacity(baseline_records: int) -> int:
    cycles = 0
    records = baseline_records
    max_cycles = max((JOURNAL_CAPACITY - records) + 30, 120)
    while records < JOURNAL_CAPACITY and cycles < max_cycles:
        ensure_test_tab(timeout=8)
        run_test_mode(TEST_MODES[cycles % len(TEST_MODES)])
        cycles += 1
        ensure_test_tab(timeout=8)
        # Каждая проба — полная выгрузка журнала (~0.45 с/запись, до ~95 с
        # при 200 записях), поэтому у порога пробуем раз в 5 циклов, а не
        # каждый цикл; реальный счётчик добирается top-up-циклами ниже.
        if cycles % 10 == 0 or (records + 10 >= JOURNAL_CAPACITY and cycles % 5 == 0):
            records = probe_log_events()
            wait_journal_idle()
            ensure_test_tab(timeout=8)
            log(f"  fill cycle {cycles}: events={records}")
        else:
            records = min(records + 2, JOURNAL_CAPACITY)
            log(f"  fill cycle {cycles}: events≈{records}")
    records = probe_log_events()
    wait_journal_idle()
    ensure_test_tab(timeout=8)
    while records < JOURNAL_CAPACITY and cycles < max_cycles:
        run_test_mode(TEST_MODES[cycles % len(TEST_MODES)])
        cycles += 1
        ensure_test_tab(timeout=8)
        records = probe_log_events()
        wait_journal_idle()
        ensure_test_tab(timeout=8)
        log(f"  top-up cycle {cycles}: events={records}")
    if records < JOURNAL_CAPACITY:
        raise AssertionError(f"Journal fill stalled at {records}/{JOURNAL_CAPACITY}")
    return records


def verify_journal_and_rotation(results: list[tuple[str, str]]) -> None:
    step("Основной: журнал")
    if FILL_JOURNAL:
        ensure_test_tab(timeout=12)
        events = probe_log_events()
        wait_journal_idle()
        records, seq_min, seq_max = journal_stats_from_logcat()
        if records == 0:
            records = events
    else:
        records, seq_min, seq_max = reload_journal()
    log(f"Journal: {records} records seq_min={seq_min} seq_max={seq_max}")

    if not FILL_JOURNAL and records < len(TEST_MODES):
        raise AssertionError(f"Journal has only {records} records")

    if not FILL_JOURNAL:
        log(f"Journal rotation skipped (use --fill-journal); {records} records")
        results.append(("journal_rotation", "SKIP"))
        results.append(("journal", "OK"))
        return

    baseline_min = seq_min
    baseline_records = records

    if records < JOURNAL_CAPACITY:
        step(f"Основной: наполнение журнала до {JOURNAL_CAPACITY}")
        records = fill_journal_to_capacity(records)
        ensure_test_tab(timeout=12)
        events = probe_log_events()
        wait_journal_idle()
        records, seq_min, seq_max = journal_stats_from_logcat()
        if records == 0:
            records = events
        log(f"Journal full: {records} records seq_min={seq_min} seq_max={seq_max}")

    step("Основной: переполнение и ротация (+10 режимов)")
    for i in range(10):
        ensure_test_tab(timeout=8)
        run_test_mode(TEST_MODES[i % len(TEST_MODES)])
        ensure_test_tab(timeout=8)
    ensure_test_tab(timeout=12)
    events = probe_log_events()
    wait_journal_idle()
    records, seq_min, seq_max = journal_stats_from_logcat()
    if records == 0:
        records, seq_min, seq_max = reload_journal()
    log(f"Journal after overflow: events={events} records={records} seq_min={seq_min} seq_max={seq_max}")

    if events > JOURNAL_CAPACITY:
        raise AssertionError(f"LOG_INFO events={events} exceeds capacity {JOURNAL_CAPACITY}")
    if records > JOURNAL_CAPACITY:
        raise AssertionError(f"Exported records={records} exceeds capacity {JOURNAL_CAPACITY}")
    if events < JOURNAL_CAPACITY:
        raise AssertionError(f"Expected {JOURNAL_CAPACITY} exportable events, got {events}")

    if baseline_min is not None and seq_min is not None and seq_min <= baseline_min:
        raise AssertionError(
            f"Rotation failed: seq_min {baseline_min} -> {seq_min} (expected increase after overflow)",
        )
    log(f"Journal rotation OK: export capped at {events}, seq {baseline_min} -> {seq_min}")
    results.append(("journal_rotation", "OK"))
    results.append(("journal", "OK"))


def change_device_name_and_password(results: list[tuple[str, str]]) -> None:
    step("Основной: изменение имени")
    ensure_test_tab()
    tap_bottom_nav("Настройки")
    wait_text("Настройки")
    tap_text("Имя устройства", clickable=None)
    wait_text("Изменение имени")
    fill_field("Имя устройства", DEVICE_NAME_NEW)
    tap_back()
    wait_text("Настройки", timeout=12)
    results.append(("rename_ui", "OK"))

    step("Основной: изменение пароля")
    tap_text("Пароль", clickable=None)
    wait_text("Изменение пароля")
    fill_field("Текущий пароль", PASSWORD)
    fill_field("Новый пароль", PASSWORD_NEW)
    fill_field("Повторите пароль", PASSWORD_NEW)
    tap_back()
    wait_text("Настройки", timeout=12)
    results.append(("password_ui", "OK"))


def phase_main(results: list[tuple[str, str]]) -> None:
    phase_banner("ОСНОВНОЙ: испытания / журнал / настройки")
    ensure_test_tab()

    if not JOURNAL_ONLY:
        step("Основной: испытания")
        for mode in TEST_MODES:
            step(f"  режим {mode}")
            run_test_mode(mode)
            results.append((f"mode_{mode}", "OK"))

    if NO_JOURNAL:
        # Журнал и экспорт (он читает журнал из состояния) пропущены —
        # это самая долгая фаза (~95 с на полную выгрузку 200 записей).
        step("Основной: журнал/экспорт пропущены (--no-journal)")
        results.append(("journal", "SKIP"))
        results.append(("export", "SKIP"))
        change_device_name_and_password(results)
        return

    verify_journal_and_rotation(results)

    if JOURNAL_ONLY:
        return

    step("Основной: экспорт журнала")
    records, _, _ = journal_stats_from_logcat()
    if records == 0:
        reload_journal()
    e2e_broadcast("com.thebutton.ble.E2E_EXPORT_CSV")
    wait_logcat("E2E export done", timeout=12)
    results.append(("export", "OK"))

    change_device_name_and_password(results)


def extract_device_mac(texts: list[str]) -> str:
    for text in texts:
        match = MAC_RE.search(text)
        if match:
            return match.group(1).upper()
    raise RuntimeError(f"BLE MAC not found in scan UI: {texts}")


def visible_texts(nodes: Iterable[Node]) -> list[str]:
    return [n.label for n in nodes if n.label]


def screen_text() -> str:
    return "\n".join(visible_texts(dump_nodes()))


def find_node(nodes: list[Node], *patterns: str, clickable: bool | None = None) -> Node | None:
    for node in nodes:
        hay = f"{node.text} {node.desc}"
        if not all(re.search(p, hay, re.I) for p in patterns):
            continue
        if clickable is not None and node.clickable != clickable:
            continue
        return node
    return None


def tap_node(node: Node) -> None:
    x, y = node.center
    adb("shell", "input", "tap", str(x), str(y))


def tap_text(*patterns: str, clickable: bool | None = True) -> bool:
    nodes = dump_nodes()
    node = find_node(nodes, *patterns, clickable=clickable)
    if not node:
        return False
    tap_node(node)
    return True


def pairing_dialog_likely(joined: str) -> bool:
    low = joined.lower()
    if "сопряж" in low or "pair" in low:
        return True
    if "запрос на соединение bluetooth" in low:
        return True
    if "показать на объекте" in low and "подключение к устройству" in low:
        return True
    return False


def accept_bluetooth_pairing(force: bool = False) -> bool:
    if force and not pairing_dialog_foreground():
        nodes = dump_nodes()
        joined = "\n".join(visible_texts(nodes))
        if not pairing_dialog_likely(joined):
            return False
    else:
        nodes = dump_nodes()
        joined = "\n".join(visible_texts(nodes))
        if not force and not pairing_dialog_likely(joined):
            return False

    button_patterns = (
        r"Устан\.?\s*сопряж",
        r"Установить сопряжение",
        r"^Подключить$",
        r"^Принять$",
        r"^Разрешить$",
        r"^Готово$",
        r"^Pair$",
        r"^Pair device$",
        r"^Сопряжение$",
        r"^OK$",
    )
    candidates: list[Node] = []
    for node in nodes:
        label = node.label.strip()
        # Samsung exposes the dialog title and its positive action with very
        # similar text. Tapping the title leaves Android in WAIT_APP_RSP until
        # SMP times out, so only accept a real, enabled accessibility action.
        if node.clickable and node.enabled and any(re.search(p, label, re.I) for p in button_patterns):
            candidates.append(node)
    if candidates:
        # Positive actions are at the bottom/right of the system dialog.
        tap_node(max(candidates, key=lambda n: (n.bounds[1], n.bounds[0])))
        log("pairing: tapped button from UI tree")
        return True

    return False


def poll_pairing_dialog(duration: float = 0.0, interval: float = POLL) -> None:
    """Accept pairing dialog if visible; returns immediately unless duration > 0."""
    deadline = time.time() + duration
    while True:
        if pairing_dialog_foreground() and accept_bluetooth_pairing(force=True):
            log("pairing: accepted foreground dialog")
            return
        if duration <= 0 or time.time() >= deadline:
            return
        time.sleep(interval)


def wait_text(*patterns: str, timeout: float = 30.0, interval: float = POLL) -> list[str]:
    deadline = time.time() + timeout
    texts: list[str] = []
    while time.time() < deadline:
        dismiss_runtime_permissions()
        texts = visible_texts(dump_nodes())
        joined = "\n".join(texts)
        if all(re.search(p, joined, re.I) for p in patterns):
            return texts
        time.sleep(interval)
    raise TimeoutError(f"Timeout waiting for {patterns!r}; saw: {texts}")


def wait_any(*patterns: str, timeout: float = 30.0, interval: float = POLL) -> list[str]:
    deadline = time.time() + timeout
    texts: list[str] = []
    while time.time() < deadline:
        dismiss_runtime_permissions()
        texts = visible_texts(dump_nodes())
        joined = "\n".join(texts)
        if any(re.search(p, joined, re.I) for p in patterns):
            return texts
        time.sleep(interval)
    raise TimeoutError(f"Timeout waiting for any of {patterns!r}; saw: {texts}")


def wait_any_with_pairing(*patterns: str, timeout: float = 45.0, interval: float = POLL) -> list[str]:
    deadline = time.time() + timeout
    texts: list[str] = []
    while time.time() < deadline:
        dismiss_runtime_permissions()
        texts = visible_texts(dump_nodes())
        joined = "\n".join(texts)
        if any(re.search(p, joined, re.I) for p in patterns):
            return texts
        if pairing_dialog_likely(joined):
            accept_bluetooth_pairing(force=True)
        time.sleep(interval)
    raise TimeoutError(f"Timeout waiting for any of {patterns!r}; saw: {texts}")


def leave_identify_if_needed(timeout: float = 12.0) -> None:
    if "E2E auth done" not in logcat_full():
        return
    deadline = time.time() + timeout
    while time.time() < deadline:
        texts = visible_texts(dump_nodes())
        if any("Провести испытание" in t for t in texts) or any("Норма" in t for t in texts):
            return
        if any("Испытание" in t for t in texts):
            try:
                tap_bottom_nav("Испытание")
            except AssertionError:
                pass
            time.sleep(0.12)
            continue
        time.sleep(0.08)


def ensure_test_tab(timeout: float = 15.0) -> list[str]:
    leave_identify_if_needed(timeout=min(timeout, 12.0))
    deadline = time.time() + timeout
    while time.time() < deadline:
        texts = visible_texts(dump_nodes())
        if any("Провести испытание" in t for t in texts) or any("Норма" in t for t in texts):
            if not any("Режим:" in t for t in texts):
                return texts
        if any("Это устройство" in t for t in texts):
            tap_text("Это устройство", clickable=None)
            time.sleep(0.1)
            continue
        if "Показать на объекте" in "\n".join(texts):
            leave_identify_if_needed(timeout=4.0)
        adb("shell", "input", "tap", "180", "2228")
        time.sleep(POLL)
    raise TimeoutError(f"Test tab not reachable; saw: {visible_texts(dump_nodes())}")


def run_test_mode(mode_title: str) -> None:
    t0 = time.perf_counter()
    wire = MODE_WIRE[mode_title]
    clear_logcat()
    e2e_broadcast("com.thebutton.ble.E2E_RUN_MODE", wire=wire)
    wait_logcat(f"E2E mode done: {mode_title}", timeout=35.0)
    log(f"  mode {mode_title}: {time.perf_counter() - t0:.1f}s")


def verify_journal_has_mode(mode_title: str, timeout: float | None = None) -> None:
    if FAST:
        return
    if timeout is None:
        timeout = 20.0
    tap_bottom_nav("Журнал")
    deadline = time.time() + timeout
    texts: list[str] = []
    while time.time() < deadline:
        texts = visible_texts(dump_nodes())
        joined = "\n".join(texts)
        if "Загрузка журнала" in joined:
            time.sleep(POLL)
            continue
        if "Не удалось загрузить" in joined:
            tap_text("Повторить", clickable=None)
            time.sleep(POLL)
            continue
        if f"Режим: {mode_title}" in joined:
            log(f"Journal OK: Режим: {mode_title}")
            return_to_test_screen()
            return
        if "Журнал пуст" in joined:
            raise AssertionError(f"Journal empty, expected Режим: {mode_title}")
        time.sleep(POLL)
    raise AssertionError(f"Journal missing 'Режим: {mode_title}'; saw: {' | '.join(texts)[:400]}")


def verify_journal() -> None:
    """Legacy helper; prefer verify_journal_and_rotation."""
    records, _, _ = reload_journal()
    if records < len(TEST_MODES):
        raise AssertionError(f"Journal has only {records} records")
    log(f"Journal: {records} records via BLE")


def wait_login_screen(timeout: float = 60.0) -> list[str]:
    deadline = time.time() + timeout
    texts: list[str] = []
    while time.time() < deadline:
        if pairing_dialog_foreground():
            accept_bluetooth_pairing(force=True)
        dismiss_runtime_permissions()
        texts = visible_texts(dump_nodes())
        joined = "\n".join(texts)
        if any(re.search(p, joined, re.I) for p in ("Первичная настройка", "Вход", r"dpls_field:Пароль")):
            return texts
        if pairing_dialog_likely(joined) or "Подключение к" in joined:
            accept_bluetooth_pairing(force=True)
        time.sleep(POLL)
    raise TimeoutError(f"Timeout waiting for login screen; saw: {texts}")


def clear_field() -> None:
    adb("shell", "input", "keycombination", "113", "29")
    time.sleep(0.1)
    adb("shell", "input", "keyevent", "67")


def set_clipboard(text: str) -> None:
    adb("shell", "cmd", "clipboard", "set-text", text)


def paste_text(value: str) -> None:
    set_clipboard(value)
    adb("shell", "input", "keyevent", "279")


def find_field(label: str) -> Node:
    nodes = dump_nodes()
    token = f"dpls_field:{label}"
    for node in nodes:
        if token in node.desc or token in node.text:
            return node
    fields = [n for n in nodes if "EditText" in n.clazz]
    order = ("Имя устройства", "Пароль", "Повторите пароль", "Текущий пароль", "Новый пароль")
    if label in order:
        idx = order.index(label)
        if idx < len(fields):
            return fields[idx]
    raise AssertionError(f"Field {label!r} not found; saw: {visible_texts(nodes)}")


def fill_field(label: str, value: str) -> None:
    tap_node(find_field(label))
    time.sleep(0.25)
    clear_field()
    paste_text(value)
    time.sleep(0.25)


def find_enabled_button(text: str) -> Node | None:
    nodes = dump_nodes()
    label = next((n for n in nodes if n.text == text), None)
    if label is None:
        return None
    lx1, ly1, lx2, ly2 = label.bounds
    best: Node | None = None
    best_area = 0
    for node in nodes:
        if not node.clickable or not node.enabled:
            continue
        x1, y1, x2, y2 = node.bounds
        if x1 <= lx1 and y1 <= ly1 and x2 >= lx2 and y2 >= ly2:
            area = (x2 - x1) * (y2 - y1)
            if area > best_area:
                best = node
                best_area = area
    return best


def wait_button_enabled(text: str, timeout: float = 20.0) -> Node:
    deadline = time.time() + timeout
    while time.time() < deadline:
        button = find_enabled_button(text)
        if button is not None:
            return button
        time.sleep(POLL)
    raise TimeoutError(f"Button {text!r} not enabled; saw: {visible_texts(dump_nodes())}")


def fill_setup_form() -> None:
    adb(
        "shell", "am", "broadcast", "-a", "com.thebutton.ble.E2E_FILL_SETUP",
        "--es", "name", DEVICE_NAME, "--es", "password", PASSWORD,
        PKG,
    )
    time.sleep(0.05)


def fill_login_form() -> None:
    adb(
        "shell", "am", "broadcast", "-a", "com.thebutton.ble.E2E_FILL_LOGIN",
        "--es", "password", PASSWORD,
        PKG,
    )
    time.sleep(0.05)


def input_text(value: str) -> None:
    escaped = value.replace(" ", "%s")
    adb("shell", "input", "text", escaped)


def clear_logcat() -> None:
    adb("logcat", "-c")


def logcat_full(tag: str = "TestDplsBle") -> str:
    return adb("logcat", "-d", "-s", tag, check=False)


def logcat_snippet(tag: str = "TestDplsBle") -> str:
    return logcat_full(tag).strip()[-4000:]


def close_export_screen(timeout: float = 10.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        texts = visible_texts(dump_nodes())
        joined = "\n".join(texts)
        if "Экспорт журнала" not in joined and any(t in joined for t in ("Экспорт", "Журнал", "Испытание")):
            return
        if any(re.search(p, joined, re.I) for p in (r"сохранить", r"save", r"downloads")):
            tap_text("СОХРАНИТЬ", clickable=None) or tap_text("Save", clickable=None)
            time.sleep(POLL)
            continue
        if tap_text("‹", clickable=None):
            time.sleep(POLL)
            continue
        adb("shell", "input", "keyevent", "4")
        time.sleep(POLL)
    raise TimeoutError(f"Export screen still open; saw: {visible_texts(dump_nodes())}")


def tap_back() -> bool:
    return tap_text("‹", clickable=None)


def ensure_app_foreground() -> None:
    texts = visible_texts(dump_nodes())
    joined = "\n".join(texts)
    if PKG.split(".")[-1] in joined.lower() or any(
        token in joined for token in ("Настройки", "Испытание", "Журнал", "Test-DPLS", "dpls_field:")
    ):
        return
    adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(0.1 if FAST else 0.25)


def tap_bottom_nav(label: str) -> None:
    nodes = dump_nodes()
    candidates = [n for n in nodes if n.label == label]
    if not candidates:
        raise AssertionError(f"Nav item {label!r} not found; saw: {visible_texts(nodes)}")
    screen_h = max((n.bounds[3] for n in nodes), default=2400)
    nav_candidates = [n for n in candidates if n.center[1] > screen_h * 0.72]
    tap_node(max(nav_candidates or candidates, key=lambda n: n.bounds[1]))


def return_to_test_screen(timeout: float = 20.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        dismiss_runtime_permissions()
        texts = visible_texts(dump_nodes())
        joined = "\n".join(texts)
        if any("Провести испытание" in t for t in texts) or "Вернуть в Норму" in joined:
            return
        if any(token in joined for token in ("Изменение имени", "Изменение пароля", "О устройстве", "Экспорт журнала", "Выбор испытания", "Подтверждение")):
            tap_back()
            time.sleep(POLL)
            continue
        if "Подключение" in joined or "Восстановление" in joined or "Загрузка журнала" in joined:
            time.sleep(POLL)
            continue
        try:
            tap_bottom_nav("Испытание")
        except AssertionError:
            adb("shell", "input", "keyevent", "4")
        time.sleep(POLL)
    raise TimeoutError(f"Test screen not reachable; saw: {visible_texts(dump_nodes())}")


def bluetooth_snippet() -> str:
    return adb("logcat", "-d", check=False)


def bonded_dpls_devices() -> list[tuple[str, str]]:
    output = adb("shell", "dumpsys", "bluetooth_manager", check=False)
    devices: list[tuple[str, str]] = []
    in_bonded = False
    for line in output.splitlines():
        if "Bonded devices:" in line:
            in_bonded = True
            continue
        if in_bonded:
            if not line.strip():
                break
            match = BONDED_DPLS_RE.search(line)
            if match and DPLS_BOND_NAME.search(match.group(2)):
                devices.append((match.group(1).upper(), match.group(2)))
    return devices


def tap_first_matching(nodes: list[Node], *patterns: str, clickable: bool | None = None) -> bool:
    for node in nodes:
        hay = f"{node.text} {node.desc}"
        if clickable is not None and node.clickable != clickable:
            continue
        if all(re.search(pattern, hay, re.I) for pattern in patterns):
            tap_node(node)
            return True
    return False


def confirm_unpair_dialog() -> None:
    deadline = time.time() + 10.0
    while time.time() < deadline:
        try:
            nodes = dump_nodes()
            joined = "\n".join(visible_texts(nodes))
        except RuntimeError:
            joined = ""
            nodes = []
        if "Разорвать связь" not in joined and "Разорвать соединение" not in joined:
            return
        for node in nodes:
            if node.text == "Разорвать соединение" and node.clickable:
                tap_node(node)
                time.sleep(0.25)
                return
        adb("shell", "input", "tap", "540", "2121")
        time.sleep(0.25)
        return
    raise TimeoutError("Unpair confirmation dialog not handled")


def forget_one_dpls_bond() -> bool:
    adb("shell", "am", "start", "-a", "android.settings.BLUETOOTH_SETTINGS")
    time.sleep(0.35)
    nodes = dump_nodes()
    if not tap_first_matching(nodes, r"Test-DPLS", clickable=True):
        if not tap_first_matching(nodes, r"Test-DPLS"):
            return False
    time.sleep(0.35)
    nodes = dump_nodes()
    if not tap_first_matching(nodes, r"Разорвать соединение", clickable=True):
        if not tap_first_matching(nodes, r"Разорвать соединение"):
            raise RuntimeError(f"Unpair button missing; saw: {visible_texts(nodes)}")
    confirm_unpair_dialog()
    time.sleep(0.35)
    return True


def remove_all_dpls_bonds(max_attempts: int = 12) -> list[str]:
    adb("shell", "am", "force-stop", PKG, check=False)
    before = bonded_dpls_devices()
    if not before:
        log("bonds: no paired DPLS devices")
        return []
    log("bonds: found " + ", ".join(f"{name} ({mac})" for mac, name in before))
    grant_permissions()
    adb("shell", "am", "start", "-n", ACTIVITY, check=False)
    time.sleep(0.35)
    clear_logcat()
    e2e_broadcast("com.thebutton.ble.E2E_UNPAIR_ALL")
    deadline = time.time() + 12.0
    while time.time() < deadline:
        if "E2E unpair done" in logcat_full():
            current = bonded_dpls_devices()
            if not current:
                log("bonds: cleared via removeBond")
                adb("shell", "input", "keyevent", "3", check=False)
                reset_bluetooth_adapter()
                return [mac for mac, _ in before]
        current = bonded_dpls_devices()
        if not current:
            log("bonds: cleared via removeBond")
            adb("shell", "input", "keyevent", "3", check=False)
            reset_bluetooth_adapter()
            return [mac for mac, _ in before]
        time.sleep(0.25)
    log("bonds: removeBond slow, retrying UI fallback")
    removed = remove_all_dpls_bonds_ui(max_attempts)
    if removed:
        reset_bluetooth_adapter()
    return removed


def remove_all_dpls_bonds_ui(max_attempts: int = 12) -> list[str]:
    adb("shell", "am", "force-stop", PKG, check=False)
    removed: list[str] = []
    before = bonded_dpls_devices()
    if not before:
        log("bonds: no paired DPLS devices")
        return removed
    log("bonds: found " + ", ".join(f"{name} ({mac})" for mac, name in before))
    for _ in range(max_attempts):
        current = bonded_dpls_devices()
        if not current:
            break
        mac_before = current[0][0]
        if not forget_one_dpls_bond():
            break
        current_after = bonded_dpls_devices()
        if len(current_after) >= len(current):
            raise RuntimeError(
                f"Failed to unpair {mac_before}; still bonded: {current_after}",
            )
        removed.append(mac_before)
        log(f"bonds: removed {mac_before}")
    remaining = bonded_dpls_devices()
    if remaining:
        raise RuntimeError(f"DPLS bonds remain: {remaining}")
    adb("shell", "input", "keyevent", "3", check=False)
    time.sleep(0.15)
    return removed


def grant_permissions() -> None:
    for perm in (
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.POST_NOTIFICATIONS",
    ):
        adb("shell", "pm", "grant", PKG, perm, check=False)


def prepare_app(clear_data: bool = True) -> None:
    if clear_data:
        adb("shell", "pm", "clear", PKG)
    else:
        adb("shell", "am", "force-stop", PKG)
    grant_permissions()
    adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(0.15 if FAST else 0.35)
    dismiss_runtime_permissions()


def dismiss_runtime_permissions() -> None:
    for _ in range(4):
        texts = visible_texts(dump_nodes())
        joined = "\n".join(texts)
        if "местоположен" in joined.lower():
            tap_text("При использовании приложения", clickable=None) or tap_text("Только в этот раз", clickable=None)
            time.sleep(POLL)
            continue
        if "поблизости" in joined.lower() or "nearby" in joined.lower():
            tap_text("Разрешить", clickable=None)
            time.sleep(POLL)
            continue
        if "bluetooth" in joined.lower() and "сопряж" not in joined.lower():
            tap_text("Разрешить", clickable=None) or tap_text("При использовании", clickable=None) or tap_text("Только в этот раз", clickable=None)
            time.sleep(POLL)
            continue
        if "уведомлен" in joined.lower():
            tap_text("Разрешить", clickable=None)
            time.sleep(POLL)
            continue
        if pairing_dialog_likely(joined):
            accept_bluetooth_pairing(force=True)
            time.sleep(POLL)
            continue
        break


def wait_for_device(name: str = "Test-DPLS", timeout: float = 20.0) -> list[str]:
    deadline = time.time() + timeout
    while time.time() < deadline:
        dismiss_runtime_permissions()
        texts = visible_texts(dump_nodes())
        if any(name in t for t in texts):
            return texts
        tap_text("Обновить", clickable=None) or tap_text("Обновление", clickable=None)
        time.sleep(0.2 if FAST else 0.5)
    raise TimeoutError(f"Device {name!r} not found in scan")


def tap_device(name: str = "Test-DPLS") -> None:
    nodes = dump_nodes()
    device_node = next((n for n in nodes if name in n.text), None)
    if device_node is None:
        raise RuntimeError(f"Device {name!r} not found")

    best: Node | None = None
    best_area = 0
    dx1, dy1, dx2, dy2 = device_node.bounds
    for candidate in nodes:
        if not candidate.clickable:
            continue
        x1, y1, x2, y2 = candidate.bounds
        if x1 <= dx1 and y1 <= dy1 and x2 >= dx2 and y2 >= dy2:
            area = (x2 - x1) * (y2 - y1)
            if area > best_area:
                best = candidate
                best_area = area
    tap_node(best if best is not None else device_node)


def pairing_dialog_foreground() -> bool:
    focus = adb("shell", "dumpsys", "window", "displays", check=False)
    return "BluetoothPairingDialog" in focus


def wait_identify_active(timeout: float | None = None) -> list[str]:
    if timeout is None:
        timeout = 4.0 if FAST else 12.0
    deadline = time.time() + timeout
    texts: list[str] = []
    while time.time() < deadline:
        poll_pairing_dialog(duration=0)
        dismiss_runtime_permissions()
        texts = visible_texts(dump_nodes())
        joined = "\n".join(texts)
        if "1 Гц" in joined:
            return texts
        time.sleep(POLL)
    log(f"identify: LED label not seen in {timeout:.0f}s, continuing")
    return texts


def wait_identify_screen(timeout: float = 45.0) -> list[str]:
    deadline = time.time() + timeout
    texts: list[str] = []
    while time.time() < deadline:
        if pairing_dialog_foreground():
            accept_bluetooth_pairing(force=True)
        dismiss_runtime_permissions()
        texts = visible_texts(dump_nodes())
        joined = "\n".join(texts)
        if "Показать на объекте" in joined:
            return texts
        time.sleep(POLL)
    raise TimeoutError(f"Identify screen not reached; saw: {texts}")


def assert_no_ff_address(texts: list[str]) -> None:
    joined = "\n".join(texts)
    if "FF:FF:FF:FF:FF:FF" in joined.upper():
        raise AssertionError("scan shows invalid FF:FF:FF:FF:FF:FF address")


def step(title: str) -> None:
    log(f"\n=== {title} ===")


def main() -> int:
    results: list[tuple[str, str]] = []
    timer = Timer()
    clear_logcat()

    try:
        if not MAIN_ONLY:
            if KEEP_BOND:
                log("bonds: keep existing pairing (--keep-bond / --journal-only)")
                prepare_app(clear_data=False)
                timer.mark("app_ready")
            else:
                step("0. Remove bonded DPLS")
                removed = remove_all_dpls_bonds()
                timer.mark("bonds_cleared")
                results.append(("unpair_dpls", "OK" if not bonded_dpls_devices() else "FAIL"))
                if removed:
                    log(f"bonds: cleared {len(removed)} device(s)")
                prepare_app(clear_data=bool(removed) and not FAST)
                timer.mark("app_ready")

        if not MAIN_ONLY:
            phase_start(results, timer)
            if CONNECT_ONLY or START_ONLY:
                timer.report()
                log("\n=== START PHASE OK ===")
                log_results(results)
                return 0

        if MAIN_ONLY:
            if KEEP_BOND:
                grant_permissions()
                ensure_app_foreground()
            else:
                prepare_app(clear_data=False)
            ensure_test_tab(timeout=20)

        phase_main(results)

        step("Завершение: disconnect")
        adb("shell", "am", "force-stop", PKG)
        results.append(("disconnect", "OK"))

    except Exception as exc:  # noqa: BLE001
        texts = visible_texts(dump_nodes())
        log(f"FAIL: {exc}")
        log("UI dump: " + " | ".join(t for t in texts if t))
        timer.report()
        report_ble_timeline(logcat_full())
        log("--- TestDplsBle ---")
        log(logcat_snippet())
        log("\n=== PARTIAL RESULTS ===")
        log_results(results)
        return 1

    log_results(results)
    timer.report()
    return 0


def log_results(results: list[tuple[str, str]]) -> None:
    log("\n=== RESULTS ===")
    for name, status in results:
        log(f"{name}: {status}")


if __name__ == "__main__":
    sys.exit(main())
