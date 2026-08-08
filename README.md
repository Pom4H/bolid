# Test-DPLS

Стек безопасного BLE-управления испытательным устройством ДПЛС: прошивка для
PHY6252 (плата PB-03F-Kit), Android- и iOS-клиенты. Протокол, аутентификация и
хранение журнала описаны в [Firmware/README.md](Firmware/README.md); клиенты —
в [TestDPLS/README.md](TestDPLS/README.md) и [TestDPLS-iOS/README.md](TestDPLS-iOS/README.md).

## Структура

| Каталог | Содержимое |
|---|---|
| `Firmware/` | Переносимое ядро сервера (C99, хостовые тесты) + адаптер PHY62XX SDK 3.1.2 и target-проект |
| `TestDPLS/` | Android-клиент (Kotlin, Jetpack Compose, minSdk 33) |
| `TestDPLS-iOS/` | iPhone-клиент (Swift, SwiftUI, iOS 16+) |
| `tools/` | Сборка/прошивка/UART-отладка |
| `tools/e2e/` | E2E-обвязка: телефон + плата через adb |
| `docs/hardware/` | Схема PB-03F-Kit |
| `pvvx-PHY62x2/` | Вендорные утилиты прошивки PHY62xx по UART |
| `tmp/` | Рабочие логи и артефакты (не под git) |

## Прошивка

Собираются два образа — с измерением напряжений (ADC) и без него:

| Образ | Команда сборки | Когда использовать |
|---|---|---|
| **С ADC** (основной) | `DPLS_ADC=1 tools/build_firmware.sh tmp/test-dpls-sdk-3.1.2.hex` | Реальная плата с силовой частью: меряет напряжение ДПЛС (P20) и резерва (P23) |
| **Без ADC** | `DPLS_ADC=0 tools/build_firmware.sh tmp/test-dpls-adcoff.hex` | Голый PB-03F-Kit без силовой части: входы ADC висят в воздухе, «низкий резерв» мгновенно снимал бы тестовые режимы |

`DPLS_ADC=1` — значение по умолчанию. CI (workflow `firmware-target.yml`)
собирает оба образа и выкладывает их одним artifact'ом
(`test-dpls-phy6252-sdk-3.1.2`: hex + axf + map на каждый вариант).

```sh
# ядро на хосте: сборка + тесты
cmake -S Firmware -B Firmware/build && cmake --build Firmware/build
ctest --test-dir Firmware/build --output-on-failure

# полная прошивка (cbuild + AC6 из vcpkg), ADC по умолчанию включён
tools/build_firmware.sh

# прошивка платы (см. подсказки про кнопку KEY1 в самом скрипте);
# без --erase журнал и настройки в SNV сохраняются,
# с --erase — полный сброс, включая пароль (устройство снова некоммишено)
tools/flash_firmware.sh tmp/test-dpls-sdk-3.1.2.hex [--erase]

# UART-лог платы (115200); лог загрузки — по короткому нажатию KEY1
python3 tools/serial_capture.py 20 --no-reset
```

## Подключение к силовой части

Распиновка управляющих и измерительных сигналов PB-03F-Kit ↔ прототип силовой
части (таблица режимов — в правом нижнем углу):

![Распиновка PB-03F-Kit ↔ силовая часть](docs/hardware/pb03f-kit-power-pinout.png)

Логика управления 3,3 В; безопасное состояние по умолчанию — все управляющие
сигналы `0` (режим «Норма»).

## Android

```sh
cd TestDPLS && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    ./gradlew installDebug
```

## iOS

```sh
open TestDPLS-iOS/TestDPLS.xcodeproj   # Xcode 15+, физический iPhone
```

Подробности — [TestDPLS-iOS/README.md](TestDPLS-iOS/README.md).

## E2E на живых устройствах

Телефон по adb (Wi-Fi или USB) + прошитая плата в радиусе BLE:

```sh
python3 tools/e2e/phone_e2e_test.py                # полный прогон
python3 tools/e2e/phone_e2e_test.py --journal-only # ротация журнала на 200
python3 tools/e2e/journal_reboot_check.py          # журнал после ребута (нажать KEY1 до запуска)
```

Проверка софта на реальном железе (пины, светодиод, ADC, автоизоляция,
калибровка, приёмка ТЗ) — по [чеклисту bring-up](docs/bring-up-checklist.md).

## Аппаратные особенности PB-03F-Kit

- **KEY1** (шелк RST/PROG) через PMOS отключает питание чипа: удержание — выкл,
  отпускание — перезапуск питания. Вход в UART-загрузчик: зажать KEY1, запустить
  прошивающий скрипт, отпустить на строке «Turn on the power».
- **RTS/DTR CH340 не разведены** — программный сброс и автовход в загрузчик
  невозможны.
- **Кнопка заводского сброса пароля — на P24** (по финальной распиновке, см.
  `Firmware/phy6252/dpls_board.h`). На PB-03F-Kit кнопки на P24 нет — физический
  сброс требует перемычки P24↔GND (удержание 5 с). KEY2 «Restore» кита (P15)
  не используется.
- Сборка идёт с `-fshort-enums`: раскладка структур на таргете не совпадает с
  хостовой (проверять смещения — по карте линкера, не по хостовому компилятору).
- Watchdog 2 с (`main.c`) взводится до старта OSAL: длинные init-пути обязаны
  вызывать `hal_watchdog_feed()`.
