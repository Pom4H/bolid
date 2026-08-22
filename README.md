# Test-DPLS

Firmware и мобильное ПО для BLE-тестера ДПЛС на PHY6252 / PB-03F.

Текущие версии:

- PHY6252 firmware: **1.4.2**;
- Android/iOS: **1.4.1**;
- wire protocol: **v2**.

## Firmware DX

У production firmware один build path и один application HEX.

```sh
git clone https://github.com/Pom4H/bolid.git
cd bolid

tools/build_firmware.sh
tools/flash_firmware.sh
```

Production toolchain закреплён проектом:

- PHY62XX SDK **3.1.2**;
- CMSIS-Toolbox **2.14.1**;
- Arm Compiler **6.24.0**;
- CMake **3.31.12**;
- Ninja **1.13.2**.

В CI окружение активируется через официальный CMSIS Actions bootstrap. `tools/build_firmware.sh` не выбирает компилятор и не содержит второго способа установки toolchain: он только собирает production target и создаёт `tmp/test-dpls.hex`.

Отдельный диагностический образ с UART-логом, счётчиками power constraints и
программным переходом в ROM-загрузчик без KEY1 собирается так:

```sh
tools/build_debug_firmware.sh
```

Результат — `tmp/test-dpls-debug-rom.hex`. В нём включены `DEBUG_INFO=1`,
`DPLS_POWER_DIAG_LOG=1` и dev-only UART handoff: приложение принимает guard-токен
на 115200, переводит выходы в `NORMAL`, завершает offline flash work, делает
boot-info невалидным; после reset ROM принимает штатную синхронизацию
`UXTDWU@9600`.
Этот образ не используют для абсолютного измерения тока: UART trace сам меняет
нагрузку. Для A/B тока без логирования остаётся `tools/build_power_ab.sh`.

Первая установка dev-образа требует одного ручного входа в ROM:

```sh
tools/flash_debug_firmware.sh --initial-manual
```

После неё следующие dev-обновления выполняются через Firmverse без KEY1:
скрипт посылает UART BREAK и токен приложению, приложение обнуляет
`boot_info.part_count`, перезагружается в ROM, а штатный программатор Firmverse
прошивает и запускает новый образ:

```sh
tools/flash_debug_firmware.sh
```

Порт можно задать явно через `--port /dev/cu...`; без него используется
автоопределение Firmverse. Это намеренно отдельный dev-инструмент: release HEX
не включает UART handoff и диагностику энергопотребления.
Wrapper также явно передаёт Firmverse boot start `0x1fff1838`: для SDK 3.1.2
это база jump/vector table, тогда как type-05 entry в GNU HEX указывает прямо
на `Reset_Handler` и не подходит для поля start в PHY62xx boot-info.

## Прошивка PB-03F

```sh
tools/flash_firmware.sh
```

Штатный USB-UART путь PB-03F использует ручной вход в ROM: скрипт просит зажать **KEY1**, выполняет handshake `UXTDWU` на 9600 бод и пишет application HEX vendor-командой `wh`.

Можно явно указать HEX и порт:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex --port /dev/cu.wchusbserial110
```

Обычная прошивка не стирает factory sector. `--erase` очищает только рабочую SNV-область, когда это явно требуется.

## Один production artifact

CI строит один PHY6252 HEX. Этот же artifact:

1. публикуется как release evidence;
2. передаётся Firmverse для strict CPU/MMIO boot-проверки;
3. используется flash harness;
4. прошивается на PB-03F.

Отдельной альтернативной target-сборки нет. Поэтому emulator, release и реальная плата проверяют один и тот же бинарник.

## Архитектурные правила

1. **Firmware владеет hardware safety.** Телефон не может обойти таймауты, автоизоляцию и безопасный `NORMAL`.
2. **Kotlin `commonMain` владеет общим поведением Android/iOS.** Здесь находятся `DplsClient`, protocol/crypto/domain/session и Compose UI.
3. **Platform-код только адаптирует OS API.** Android/iOS не содержат вторых controllers, protocol codecs или независимых UI.
4. **Production HEX проверяется внешним Firmverse.** Bolid не хранит собственный PHY6252/ZMU emulator.
5. **Boot не зависит от provisioning sidecar.** Плата запускает GAP и advertising из одного application HEX.

Подробнее: [docs/architecture.md](docs/architecture.md).

## Структура репозитория

| Путь | Назначение |
|---|---|
| `firmware/` | переносимый C99 server, PHY6252 adapter, host tests и production target |
| `firmware/sim/` | быстрый simulator для lab/replay/Soft-BLE; не исполняет target HEX |
| `mobile/core/` | общий KMP controller, protocol, crypto, domain/session, Compose UI и platform transports |
| `mobile/android/` | Android shell и debug E2E |
| `mobile/ios/` | Xcode shell и минимальный Swift bootstrap |
| `mobile/web/` | тот же Compose UI поверх `LabBleTransport` |
| `docs/` | архитектура, bring-up и технические reference |
| `tools/` | build, flash, проверки и lab/session utilities |

Точная версия PHY62XX SDK закреплена в `firmware/sdk/phy6252-sdk.env`.

## Safety model

Firmware гарантирует:

- boot/disconnect/error → `NORMAL`;
- hard timeout опасного режима;
- session timeout → `NORMAL`;
- low reserve / real-short override;
- break-before-make;
- persistent authentication lock;
- сериализованный ATT TX.

Успешный GATT write не считается подтверждением физического режима. UI меняет состояние только после `COMMAND_RESULT` и последующего `STATE_REPORT`.

## Проверки

```sh
bash tools/check_all.sh
bash tools/check_mobile.sh
bash tools/soft_ble_e2e.sh
bash tools/dpls_lab.sh
```

`tools/check_repo_layout.sh` и `tools/test_ci_contract.py` дополнительно защищают правило одного production toolchain/source graph.

## Boot order PHY6252

```text
power/reset
  ↓
RAM retention + SNV mount
  ↓
BLE identity prepare
  ↓
DPLS init
  ↓
GAPRole_StartDevice
  ↓
GAPROLE_STARTED
  ↓
advertising ON
  ↓
idle/deferred flash work
```

Boot journal не задерживает GAP/advertising. Deferred flash обслуживается без active BLE link.

## BLE/GATT

| Элемент | UUID |
|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` |
| RX / WRITE | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` |
| TX / INDICATE+NOTIFY | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` |

CCCD доступен до SMP, а encrypted RX characteristic является защищённой protocol boundary.

## Hardware revision 2

| Функция | GPIO |
|---|---|
| ISO_1 / ISO_2 / ISO_T | P31 / P32 / P33 |
| KZ_1 / KZ_2 / KZ_T | P14 / P16 / P17 |
| ADC +1 / +2 / +Т / reserve | P20 / P15 / P24 / P23 |
| RGB R / G / B | P7 / P11 / P18 |
| Factory reset | P34 |

Все control outputs = 0 соответствуют безопасному `NORMAL`.

Аппаратная приёмка: [docs/bring-up-checklist.md](docs/bring-up-checklist.md).
