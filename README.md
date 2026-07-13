# Test-DPLS

Стек безопасного BLE-управления испытательным устройством ДПЛС: прошивка для
PHY6252 (плата PB-03F-Kit) и Android-клиент. Протокол, аутентификация и
хранение журнала описаны в [Firmware/README.md](Firmware/README.md), клиент —
в [TheButton/README.md](TheButton/README.md).

## Структура

| Каталог | Содержимое |
|---|---|
| `Firmware/` | Переносимое ядро сервера (C99, хостовые тесты) + адаптер PHY62XX SDK 3.1.1 |
| `TheButton/` | Android-клиент (Kotlin, Jetpack Compose, minSdk 33) |
| `tools/` | Сборка/прошивка/UART-отладка |
| `tools/e2e/` | E2E-харнесс: телефон + плата через adb |
| `docs/hardware/` | Схема PB-03F-Kit |
| `pvvx-PHY62x2/` | Вендорные утилиты прошивки PHY62xx по UART |
| `tmp/` | Рабочие логи и артефакты (не под git) |

## Прошивка

```sh
# ядро на хосте: сборка + тесты
cmake -S Firmware -B Firmware/build && cmake --build Firmware/build
ctest --test-dir Firmware/build --output-on-failure

# полная прошивка (cbuild + AC6 из vcpkg) → tmp/test-dpls.hex
tools/build_firmware.sh

# прошивка платы (см. подсказки про кнопку KEY1 в самом скрипте);
# без --erase журнал и настройки в SNV сохраняются
tools/flash_firmware.sh tmp/test-dpls.hex [--erase]

# UART-лог платы (115200); лог загрузки — по короткому нажатию KEY1
python3 tools/serial_capture.py 20 --no-reset
```

## Android

```sh
cd TheButton && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    ./gradlew installDebug
```

## E2E на живых устройствах

Телефон по adb (Wi-Fi или USB) + прошитая плата в радиусе BLE:

```sh
python3 tools/e2e/phone_e2e_test.py                # полный прогон
python3 tools/e2e/phone_e2e_test.py --journal-only # ротация журнала на 200
python3 tools/e2e/journal_reboot_check.py          # журнал после ребута (нажать KEY1 до запуска)
```

## Аппаратные особенности PB-03F-Kit

- **KEY1** (шелк RST/PROG) через PMOS отключает питание чипа: удержание — выкл,
  отпускание — power-cycle. Вход в UART-бутлоадер: зажать KEY1, запустить
  флешер, отпустить на строке «Turn on the power».
- **RTS/DTR CH340 не разведены** — программный сброс и автовход в бутлоадер
  невозможны.
- **KEY2 «Restore» разведена на P15**, прошивка же ждёт кнопку заводского
  сброса на P14 (см. схему в `docs/hardware/`) — на ките физический сброс
  требует перемычки либо смены пина в `dpls_phy6252_app.c`.
- Сборка идёт с `-fshort-enums`: раскладка структур на таргете не совпадает с
  хостовой (проверять смещения — по карте линкера, не по хостовому компилятору).
- Watchdog 2 с (`main.c`) взводится до старта OSAL: длинные init-пути обязаны
  вызывать `hal_watchdog_feed()`.
