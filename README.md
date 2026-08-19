# Test-DPLS

Firmware и мобильное ПО для BLE-тестера ДПЛС на PHY6252 / PB-03F.

Текущие версии:

- PHY6252 firmware: **1.4.2**;
- Android/iOS: **1.4.1**;
- wire protocol: **v2**.

## Главные архитектурные правила

1. **Firmware владеет hardware safety.** Телефон не может обойти таймауты, автоизоляцию и безопасный `NORMAL`.
2. **Kotlin `commonMain` владеет общим поведением Android/iOS.** Здесь находятся `DplsClient`, protocol/crypto/domain/session и Compose UI.
3. **Platform-код только адаптирует OS API.** Android/iOS не содержат вторых controllers, protocol codecs или независимых UI.
4. **Production HEX проверяется внешним Firmverse.** Bolid не хранит собственный ZMU/guest HEX runner.

Подробнее: [docs/architecture.md](docs/architecture.md).

## Структура репозитория

| Путь | Назначение |
|---|---|
| `firmware/` | переносимый C99 server, PHY6252 HAL/GATT adapter, host tests и target builds |
| `firmware/phy6252_emu/` | лёгкая host-модель ATT/OSAL/SNV для продуктового simulator; не исполняет production HEX |
| `firmware/sim/` | Test-DPLS host simulator для lab/replay/Soft-BLE |
| `mobile/core/` | общий KMP controller, protocol, crypto, domain/session, Compose UI и platform transports |
| `mobile/android/` | Android shell и debug E2E |
| `mobile/ios/` | Xcode shell и минимальный Swift bootstrap |
| `mobile/web/` | тот же Compose UI поверх `LabBleTransport` |
| `docs/` | архитектура, production identity, bring-up и технические reference |
| `tools/` | build/flash/check/lab/session utilities |

Production PHY62XX SDK не vendored: точная версия **3.1.2** закреплена в `firmware/sdk/phy6252-sdk.env`.

## Серийная identity

Новая схема не поддерживает legacy identity прототипов.

Каждая плата до запуска BLE должна иметь валидный factory record:

- `serial_number` — полный 32-битный `device_id`;
- IRK/CSRK;
- источник BLE address: заводской public MAC PHY6252 либо provisioned static-random address;
- hardware revision;
- CRC.

Factory record хранится отдельно в `0x1103F000..0x1103FFFF`. Без него firmware **не начинает advertising**.

В эфире остаются только:

- BLE flags;
- фирменный 128-bit Service UUID;
- имя `Test-DPLS-XXXX`, где `XXXX` — младшие 16 бит serial.

Полный serial, firmware version, hardware revision, capabilities и пользовательское имя приходят только через `DEVICE_INFO_REPORT`. Суффикс `XXXX` не считается полным `NodeId`.

Подробно: [docs/factory-identity.md](docs/factory-identity.md).

## Mobile architecture

```text
                 Test-DPLS wire protocol v2
                          │
                ┌─────────▼─────────┐
                │ commonMain        │
                │ DplsClient        │
                │ protocol/crypto   │
                │ domain/session    │
                │ DplsApp Compose   │
                └─────────┬─────────┘
                          │ DplsTransport
                 ┌────────┴────────┐
                 │                 │
          Android adapter     iOS adapter
                 │                 │
          Android shell       Xcode shell
```

Swift не содержит отдельный BLE client или SwiftUI-копию приложения.

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

## Быстрые проверки

Полный host-side набор:

```sh
bash tools/check_all.sh
```

Mobile loop:

```sh
bash tools/check_mobile.sh
```

Soft-BLE product E2E:

```sh
bash tools/soft_ble_e2e.sh
```

Host lab с тем же Compose UI:

```sh
bash tools/dpls_lab.sh
# http://127.0.0.1:8787
```

### Production HEX / Firmverse

Реальный GCC target HEX собирается в CI и запускается через [Pom4H/firmverse](https://github.com/Pom4H/firmverse):

```yaml
- uses: Pom4H/firmverse@v1
  with:
    firmware: tmp/test-dpls-firmverse.hex
    board: pb03f-kit
    strict: 'true'
```

Старые `firmware/zmu`, `tools/zmu_*` и `third_party/phy6252-emu` удалены. Детали: [docs/chip-emulator.md](docs/chip-emulator.md).

## Сборка firmware

Host tests:

```sh
cmake -S firmware -B firmware/build
cmake --build firmware/build
ctest --test-dir firmware/build --output-on-failure
```

PHY6252 targets:

```sh
tools/build_firmware.sh keil tmp/test-dpls.hex
tools/build_firmware.sh gcc  tmp/test-dpls-gcc.hex
```

## Provisioning и прошивка платы

Для новой платы сначала создаётся индивидуальный factory record:

```sh
python3 tools/make_factory_identity.py \
  --serial 12874 \
  --hw-revision 2 \
  --binary-output tmp/factory-00012874.bin \
  --metadata tmp/factory-00012874.json
```

Затем:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex
tools/flash_factory_identity.sh tmp/factory-00012874.bin
```

Factory record пишется raw-командой в offset `0x3F000`. Не использовать application `wh` для отдельного factory HEX.

Полный chip erase стирает и factory identity, поэтому `tools/flash_firmware.sh --erase` требует явного `DPLS_ALLOW_FACTORY_ERASE=1` и после такого erase плата должна пройти provisioning заново.

## Mobile

```sh
cd mobile
./gradlew :core:testDebugUnitTest
./gradlew :core:lintDebug :android:lintDebug :android:assembleDebug
```

На macOS:

```sh
./gradlew :core:iosSimulatorArm64Test
./gradlew :core:linkDebugFrameworkIosSimulatorArm64
open ios/TestDPLS.xcodeproj
```

Для Xcode/KMP требуется Java 17.

## BLE/GATT

| Элемент | UUID |
|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` |
| RX / WRITE | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` |
| TX / INDICATE+NOTIFY | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` |

CCCD требует encrypted write. Manufacturer Specific Data в текущем контракте нет.

## Hardware revision 2

| Функция | GPIO |
|---|---|
| ISO_1 / ISO_2 / ISO_T | P31 / P32 / P33 |
| KZ_1 / KZ_2 / KZ_T | P14 / P16 / P17 |
| ADC +1 / +2 / +Т / reserve | P20 / P15 / P24 / P23 |
| RGB R / G / B | P7 / P11 / P18 |
| Factory reset | P34 |

Логика силовых управляющих сигналов — 3,3 В active-high; все нули соответствуют безопасному `NORMAL`.

Аппаратная приёмка: [docs/bring-up-checklist.md](docs/bring-up-checklist.md).
