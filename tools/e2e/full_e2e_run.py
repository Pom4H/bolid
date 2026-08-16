#!/usr/bin/env python3
"""Full E2E with settings save on top of phone_e2e_test."""

from __future__ import annotations

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from phone_e2e_test import (  # noqa: E402
    DEVICE_NAME,
    PASSWORD,
    adb,
    ensure_app_foreground,
    fill_field,
    log,
    logcat_full,
    logcat_snippet,
    report_ble_timeline,
    step,
    tap_back,
    tap_bottom_nav,
    tap_node,
    tap_text,
    timer,
    visible_texts,
    wait_any,
    wait_button_enabled,
    wait_text,
)
import phone_e2e_test as base  # noqa: E402


def save_device_name(name: str) -> None:
    tap_text("Имя устройства", clickable=None)
    wait_text("Изменение имени")
    fill_field("Имя устройства", name)
    node = wait_button_enabled("Сохранить")
    tap_node(node)
    wait_any("Настройки сохранены", "Сохранение имени", timeout=15)
    deadline = time.time() + 20
    while time.time() < deadline:
        texts = visible_texts(base.dump_nodes())
        joined = "\n".join(texts)
        if "Настройки сохранены" in joined:
            log(f"settings name saved: {name}")
            return
        if any(err in joined for err in ("отклонены", "ошибка", "Ошибка")):
            raise AssertionError(f"Name save failed: {joined}")
        time.sleep(0.2)
    raise AssertionError("Name save timeout")


def save_password(new_password: str) -> None:
    tap_text("Пароль", clickable=None)
    wait_text("Изменение пароля")
    fill_field("Текущий пароль", PASSWORD)
    fill_field("Новый пароль", new_password)
    fill_field("Повторите пароль", new_password)
    node = wait_button_enabled("Сохранить")
    tap_node(node)
    wait_any("Настройки сохранены", "Сохранение пароля", timeout=15)
    deadline = time.time() + 20
    while time.time() < deadline:
        texts = visible_texts(base.dump_nodes())
        joined = "\n".join(texts)
        if "Настройки сохранены" in joined:
            log("settings password saved")
            return
        if any(err in joined for err in ("отклонены", "ошибка", "Ошибка", "неверно")):
            raise AssertionError(f"Password save failed: {joined}")
        time.sleep(0.2)
    raise AssertionError("Password save timeout")


def main() -> int:
    base.timer = base.Timer()
    code = base.main()
    if code != 0:
        return code

    try:
        step("9b. Save device name")
        adb("shell", "am", "start", "-n", base.ACTIVITY)
        time.sleep(0.5)
        tap_bottom_nav("Настройки")
        wait_text("Настройки")
        save_device_name("DPLS-E2E-Name")
        ensure_app_foreground()
        wait_text("Настройки")
        texts = visible_texts(base.dump_nodes())
        if not any("DPLS-E2E-Name" in t for t in texts):
            raise AssertionError(f"Name not shown in settings: {texts}")
        log("settings name visible in UI")

        step("9c. Save new password and re-login")
        new_pw = "TestDpls02"
        save_password(new_pw)
        tap_bottom_nav("Испытание")
        wait_text("Провести испытание", timeout=20)
        adb("shell", "am", "force-stop", base.PKG)
        time.sleep(0.5)
        base.prepare_app(clear_data=False)
        base.wait_text("Устройства рядом")
        base.wait_for_device("Test-DPLS")
        base.tap_device("Test-DPLS")
        base.poll_pairing_dialog()
        base.wait_identify_screen(timeout=45)
        base.wait_identify_active(timeout=30)
        tap_text("Это устройство", clickable=None)
        base.poll_pairing_dialog()
        base.wait_login_screen(timeout=60)
        base.fill_login_form.__globals__["PASSWORD"] = new_pw
        adb(
            "shell",
            "am",
            "broadcast",
            "-a",
            "ru.bolid.testdpls.E2E_FILL_LOGIN",
            "--es",
            "password",
            new_pw,
            base.PKG,
        )
        time.sleep(0.1)
        from phone_e2e_test import find_enabled_button, tap_node

        tap_node(find_enabled_button("Войти"))
        base.wait_any("Испытание", "Провести испытание", timeout=45)
        log("re-login with new password OK")

        step("9d. Restore original password")
        tap_bottom_nav("Настройки")
        wait_text("Настройки")
        save_password(PASSWORD)
        log("password restored to TestDpls01")

    except Exception as exc:  # noqa: BLE001
        log(f"SETTINGS FAIL: {exc}")
        log("UI: " + " | ".join(visible_texts(base.dump_nodes())))
        log("--- TestDplsBle ---")
        log(logcat_snippet())
        return 1

    log("\n=== FULL E2E WITH SETTINGS: OK ===")
    base.timer.report()
    report_ble_timeline(logcat_full())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
