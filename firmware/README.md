# Firmware Test-DPLS

PHY6252 firmware версии **1.4.2**. Код разделён на переносимый C99 server и узкий PHY6252 adapter. `firmware/sim` — быстрый protocol/UI simulator; production HEX исполняется внешним Firmverse.

## Структура

| Путь | Назначение |
|---|---|
| `src/`, `include/` | protocol, server, safety, LED, HMAC, calibration |
| `sim/` | Test-DPLS simulator для lab/replay/Soft-BLE |
| `tests/` | host behavioral/fault tests |
| `phy6252/` | HAL/GATT/ADC/persistence/board mapping |
| `targets/phy6252/` | единственный production target: CMSIS project + scatter file |
| `sdk/phy6252-sdk.env` | pin PHY62XX SDK 3.1.2 |

## Production build

```sh
tools/build_firmware.sh
tools/flash_firmware.sh
```

`tools/build_firmware.sh` всегда собирает один и тот же target Arm Compiler 6.24.0 через CMSIS-Toolbox 2.14.1. Селекторов toolchain и второго target source graph нет.

По умолчанию создаётся:

```text
tmp/test-dpls.hex
```

Другой output path можно передать единственным аргументом:

```sh
tools/build_firmware.sh tmp/rc9.hex
```

CI публикует этот artifact и передаёт его же Firmverse. На PB-03F прошивается тот же HEX.

## Safety invariants

- startup/disconnect/error → `NORMAL`;
- dangerous mode и authenticated session имеют hard timeout;
- low reserve / real-short override requested mode;
- силовые выходы переключаются break-before-make;
- auth lock хранится durable;
- один ATT request in flight;
- hardware apply error переводит physical и logical state в `NORMAL`.

## Boot / BLE

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

Текущие правила:

- XIP window: `0x11020000 + 0x20000`;
- application HEX обязан заканчиваться до SNV `0x1103C000`;
- identity использует factory PHY6252 MAC → SNV fallback → однократную генерацию;
- controller address задаётся до запуска GAP role;
- ошибка identity не блокирует advertising;
- blocking flash не выполняется при active BLE link.

## Проверки

```sh
bash tools/run_host_invariant_gate.sh
bash tools/coverage_firmware.sh
bash tools/lint_firmware.sh
bash tools/soft_ble_e2e.sh
```

Release CI отдельно проверяет host invariants, static analysis, один production target, strict Firmverse boot и mobile integration.

## BLE/GATT

| Элемент | UUID |
|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` |
| RX / WRITE | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` |
| TX / INDICATE+NOTIFY | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` |

CCCD доступен до SMP; encrypted RX — security boundary protocol layer.

## Persistent data

| SNV | Данные |
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
