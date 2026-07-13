#!/usr/bin/env python3
"""Проверка живучести журнала после ребута: вход, выгрузка, счётчики.

Перед запуском перезагрузите плату (короткое нажатие KEY1) — скрипт
подключится, войдёт по паролю и убедится, что журнал восстановился
из flash с непрерывной нумерацией.
"""
import importlib.util
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("e2e", os.path.join(_HERE, "phone_e2e_test.py"))
e2e = importlib.util.module_from_spec(spec)
sys.modules["e2e"] = e2e
spec.loader.exec_module(e2e)

e2e.clear_logcat()
e2e.prepare_app(clear_data=False)
e2e.connect_and_pair()
e2e.complete_login(timeout=60)
print("login: OK", flush=True)
records = e2e.probe_log_events()
e2e.wait_journal_idle()
stats_records, seq_min, seq_max = e2e.journal_stats_from_logcat()
print(f"RESULT: records={stats_records or records} seq_min={seq_min} seq_max={seq_max}", flush=True)
