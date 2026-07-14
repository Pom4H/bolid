#!/usr/bin/env python3
"""Проверка персистентности блокировки перебора пароля на живом железе.

Двухфазный тест (между фазами — короткое нажатие KEY1 для ребута платы):

  python3 tools/e2e/lockout_persist_check.py block
      Подключается, вводит 5 неверных паролей (с паузой >1 с, чтобы не
      сработал rate-limit) и убеждается, что устройство заблокировалось
      (AUTH_RESULT status=2). Это пишет маркер блокировки в SNV 0x84.

  <короткое нажатие KEY1 — ребут платы>

  python3 tools/e2e/lockout_persist_check.py verify
      Подключается и один раз вводит ВЕРНЫЙ пароль. Если устройство всё ещё
      блокирует вход сразу после ребута — маркер пережил перезагрузку и был
      восстановлен (ТЗ 7.3.5 + persistent-lock этапа 2). Успех = ЗАБЛОКИРОВАН.

Пароль берётся из phone_e2e_test (PASSWORD/WRONG_PASSWORD).
"""
from __future__ import annotations

import importlib.util
import os
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("e2e", os.path.join(_HERE, "phone_e2e_test.py"))
e2e = importlib.util.module_from_spec(spec)
sys.modules["e2e"] = e2e
spec.loader.exec_module(e2e)

# UI-ввод пароля идёт медленнее секунды, но подстрахуемся явной паузой, чтобы
# ни одна попытка не отсеклась rate-limit'ом (DPLS_AUTH_MIN_INTERVAL_MS = 1 с).
PACING_S = 1.4


def _connect_to_login() -> None:
    e2e.clear_logcat()
    e2e.prepare_app(clear_data=True)
    e2e.wait_text("Устройства рядом", timeout=20)
    e2e.wait_for_device("Test-DPLS")
    e2e.tap_device("Test-DPLS")
    e2e.wait_identify_led(max_connect=25.0)
    e2e.confirm_identified_device()
    e2e.wait_logcat("E2E login ready", timeout=45)


def phase_block() -> int:
    _connect_to_login()
    for attempt in range(1, e2e.AUTH_BLOCK_ATTEMPTS_FW + 1):
        e2e.submit_password(e2e.WRONG_PASSWORD)
        if attempt < e2e.AUTH_BLOCK_ATTEMPTS_FW:
            e2e.wait_logcat("E2E auth wrong", timeout=12)
            print(f"  неверный пароль {attempt}/{e2e.AUTH_BLOCK_ATTEMPTS_FW}: отклонён", flush=True)
        else:
            e2e.wait_logcat("E2E auth blocked", timeout=12)
            print(f"  неверный пароль {attempt}/{e2e.AUTH_BLOCK_ATTEMPTS_FW}: БЛОКИРОВКА", flush=True)
        time.sleep(PACING_S)
    print("BLOCK: OK — устройство заблокировано, маркер записан в SNV 0x84", flush=True)
    print(">>> Коротко нажмите KEY1 (ребут), затем запустите фазу verify", flush=True)
    return 0


def phase_verify() -> int:
    _connect_to_login()
    # Один верный пароль. Если lock пережил ребут — устройство ответит
    # блокировкой (status=2), а не входом.
    e2e.submit_password(e2e.PASSWORD)
    deadline = time.time() + 15
    while time.time() < deadline:
        log = e2e.logcat_full()
        if "E2E auth blocked" in log:
            print("VERIFY: OK — после ребута вход всё ещё заблокирован (маркер пережил перезагрузку)", flush=True)
            return 0
        if "E2E auth done" in log:
            print("VERIFY: FAIL — устройство пустило по верному паролю: блокировка НЕ пережила ребут", flush=True)
            return 1
        time.sleep(0.1)
    print("VERIFY: FAIL — нет ответа устройства за 15 с", flush=True)
    return 1


def main() -> int:
    phase = sys.argv[1] if len(sys.argv) > 1 else ""
    if phase == "block":
        return phase_block()
    if phase == "verify":
        return phase_verify()
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
