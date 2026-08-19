# Firmware Test-DPLS

PHY6252 firmware версии **1.4.2**. Код разделён на переносимый C99 server и узкий PHY6252 adapter. Быстрый host simulator живёт в `sim/`, а реальный target HEX исполняется внешним Firmverse.

## Структура

| Путь | Назначение |
|---|---|
| `src/`, `include/` | protocol, server, safety, LED, HMAC, calibration |
| `sim/` | Test-DPLS host simulator для lab/replay/Soft-BLE; private ATT transport внутри этого каталога |
| `tests/` | host behavioral/edge-case tests |
| `phy6252/` | HAL/GATT/ADC/persistence/board mapping |
| `targets/phy6252/` | Keil и GNU Arm target builds |
| `sdk/phy6252-sdk.env` | pin PHY62XX SDK 3.1.2 |

Полный vendor SDK не хранится в репозитории. Собственного PHY6252/ZMU emulator stack также нет: production target emulation выполняет [Firmverse](https://github.com/Pom4H/firmverse).

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

Относительный output path всегда нормализуется относительно корня репозитория, поэтому `make -C firmware/targets/phy6252` не может случайно положить HEX внутрь target build directory.

## Firmverse в CI

Для pull request с изменениями firmware GitHub Actions собирает настоящий GCC Intel HEX и передаёт его в Firmverse:

```yaml
- uses: Pom4H/firmverse@v1
  with:
    firmware: tmp/test-dpls-firmverse.hex
    board: pb03f-kit
    strict: 'true'
```

Bolid больше не содержит standalone `firmware/phy6252_emu`, `firmware/zmu`, `tools/zmu_*` или vendored guest emulator. `firmware/sim` остаётся только быстрым продуктовым mock для UI/protocol сценариев и не считается PHY6252 acceptance gate.

Текущая проверка не подменяет production provisioning: factory identity находится в отдельном flash sector и пока не передаётся в Action. Подробно: [`../docs/chip-emulator.md`](../docs/chip-emulator.md).

## BLE/GATT

| Элемент | UUID |
|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` |
| RX / WRITE | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` |
| TX / INDICATE+NOTIFY | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` |

CCCD защищён `GATT_PERMIT_ENCRYPT_WRITE`. Advertising содержит Service UUID, scan response — `Test-DPLS-XXXX`. Manufacturer Specific Data отсутствуют.

Полная информация о приборе приходит через `DEVICE_INFO_REPORT`: 32-битный serial/deviceId, firmware version, hardware revision, capabilities и пользовательское имя.

## Factory identity

Серийный прибор обязан иметь валидный record в `0x1103F000..0x1103FFFF`.

Record содержит serial, hardware revision, IRK/CSRK, optional static-random BLE address и CRC. Без record firmware не начинает advertising. Runtime fallback на SNV MAC или случайную identity отсутствует.

Подробнее: [`../docs/factory-identity.md`](../docs/factory-identity.md).

## Flash layout

```text
0x11020000 .. 0x1103BFFF   application XIP (0x1C000)
0x1103C000 .. 0x1103EFFF   SNV filesystem, 3 × 4 KiB
0x1103F000 .. 0x1103FFFF   factory identity, 4 KiB
```

Linker/scatter не позволяют application image занять SNV/factory sectors.

### SNV allocation

| Record/range | Данные |
|---|---|
| `0x20..0x5F` | BLE bonds |
| `0x80` | settings |
| `0x81` | initialization marker |
| `0x82` | **не используется новой identity-схемой** |
| `0x83` | ADC calibration |
| `0x84` | authentication lock |
| `0x90..0xA3` | event journal |

## Provisioning

```sh
python3 tools/make_factory_identity.py \
  --serial 12874 \
  --hw-revision 2 \
  --binary-output tmp/factory-00012874.bin \
  --metadata tmp/factory-00012874.json

tools/flash_firmware.sh tmp/test-dpls.hex
tools/flash_factory_identity.sh tmp/factory-00012874.bin
```

`flash_factory_identity.sh` пишет ровно 64 байта через raw `we 0x3F000`. Полный chip erase удаляет SNV и factory identity; после erase provisioning обязателен заново.

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
