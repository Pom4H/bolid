# Firmware Test-DPLS

PHY6252 firmware версии **1.4.2**. Код разделён на переносимый C99 server и узкий PHY6252 adapter. Быстрый host simulator живёт в `sim/`, а реальный target HEX исполняется внешним Firmverse.

## Структура

| Путь | Назначение |
|---|---|
| `src/`, `include/` | protocol, server, safety, LED, HMAC, calibration |
| `sim/` | Test-DPLS host simulator для lab/replay/Soft-BLE |
| `tests/` | host behavioral/edge-case tests |
| `phy6252/` | HAL/GATT/ADC/persistence/board mapping |
| `targets/phy6252/` | Keil и GNU Arm target builds |
| `sdk/phy6252-sdk.env` | pin PHY62XX SDK 3.1.2 |

Полный vendor SDK не хранится в репозитории. Production target emulation выполняет [Firmverse](https://github.com/Pom4H/firmverse).

## Safety invariants

- startup и BLE disconnect принудительно возвращают `NORMAL`;
- опасные режимы имеют hard timeout;
- session timeout возвращает `NORMAL`;
- low reserve и real-short isolation имеют приоритет над requested mode;
- силовые выходы переключаются break-before-make;
- application auth lock переживает reconnect/reboot;
- session token/nonces очищаются при reset link state;
- TX сериализован: один ATT PDU in flight;
- аппаратная ошибка применения режима переводит physical и logical state в `NORMAL`.

## Boot invariants

Аппаратно проверенная база — release 1.4.0. RC7 сохраняет её boot-critical порядок:

```text
power/reset
  ↓
RAM retention
  ↓
SNV filesystem mount
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

Критические правила:

- application XIP linker window остаётся `0x11020000 + 0x20000`, как в рабочем 1.4.0;
- BLE identity не читает отдельный raw factory sector;
- advertising не зависит от `identity_ready` или journal/settings pending;
- boot journal не пишет flash в окно старта GAP;
- blocking flash выполняется только отдельным OSAL turn без active BLE link.

## BLE identity

Identity использует простой путь 1.4.0:

1. vendor `check_chip_mAddr()` / `g_chipMAddr` для заводского PHY6252 MAC;
2. сохранённый MAC в SNV `0x82` как fallback;
3. генерация и сохранение MAC, если первых двух источников нет;
4. IRK/CSRK в стандартном BLE SNV;
5. `HCI_EXT_SetBDADDRCmd()` до `GAPRole_StartDevice()`.

Отдельного factory record, `.factory.bin`, `0x1103F000` и обязательного provisioning нет. Если identity не подготовилась, это не блокирует BLE advertising: имя остаётся `Test-DPLS-0000`.

## Сборка и host tests

```sh
cmake -S firmware -B firmware/build
cmake --build firmware/build
ctest --test-dir firmware/build --output-on-failure
bash tools/lint_firmware.sh
bash tools/coverage_firmware.sh
```

Soft-BLE продуктовый сценарий:

```sh
bash tools/soft_ble_e2e.sh
```

## PHY6252 target builds

```sh
tools/build_firmware.sh keil tmp/test-dpls.hex
tools/build_firmware.sh gcc  tmp/test-dpls-gcc.hex
```

Сборка создаёт только один application HEX.

## Прошивка

```sh
tools/flash_firmware.sh tmp/test-dpls.hex
```

Это одна programmer operation `wh`. Никакой raw-записи отдельного factory sector нет.

Полный erase при необходимости:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex --erase
```

После erase SNV identity/bonds/settings создаются обычными runtime путями.

## Firmverse в CI

Для release PR GitHub Actions собирает настоящий GCC Intel HEX и передаёт его в Firmverse:

```yaml
- uses: Pom4H/firmverse@v1
  with:
    firmware: tmp/test-dpls-firmverse.hex
    board: pb03f-kit
    strict: 'true'
```

`firmware/sim` остаётся быстрым продуктовым simulator и не считается PHY6252 hardware acceptance gate.

## BLE/GATT

| Элемент | UUID |
|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` |
| RX / WRITE | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` |
| TX / INDICATE+NOTIFY | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` |

CCCD доступен до SMP. Защищённая protocol boundary — RX с `GATT_PERMIT_ENCRYPT_WRITE`.

## Flash / SNV

Рабочий 1.4.0 linker window намеренно не сужается:

```text
XIP linker window: 0x11020000 .. 0x1103FFFF  (0x20000)
SNV filesystem:    0x1103C000 .. 0x1103EFFF  (3 × 4 KiB)
```

Это не означает, что application разрешено реально записывать SNV-диапазон: production HEX должен заканчиваться до `0x1103C000`. Проверять нужно фактический image, а не менять boot-visible linker geometry.

### SNV allocation продукта

| Record/range | Данные |
|---|---|
| `0x20..0x5F` | BLE bonds/keys vendor stack |
| `0x82` | fallback BLE MAC |
| `0x83` | ADC calibration |
| `0x84` | authentication lock |
| `0x85..0x86` | durable settings A/B |
| `0x90..0xA3` | event journal |

## Hardware revision 2

Source of truth: `phy6252/dpls_board.h`.

| Функция | GPIO |
|---|---|
| ISO_1 / ISO_2 / ISO_T | P31 / P32 / P33 |
| KZ_1 / KZ_2 / KZ_T | P14 / P16 / P17 |
| ADC +1 / +2 / +Т / reserve | P20 / P15 / P24 / P23 |
| RGB R / G / B | P7 / P11 / P18 |
| Factory reset | P34 |

Все control outputs = 0 соответствует `NORMAL`.
