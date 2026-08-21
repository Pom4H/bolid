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
4. **Production HEX проверяется внешним Firmverse.** Bolid не хранит собственный PHY6252/ZMU emulator.
5. **Boot не зависит от provisioning sidecar.** Плата должна запустить GAP и advertising из одного application HEX.

Подробнее: [docs/architecture.md](docs/architecture.md).

## Структура репозитория

| Путь | Назначение |
|---|---|
| `firmware/` | переносимый C99 server, PHY6252 HAL/GATT adapter, host tests и target builds |
| `firmware/sim/` | Test-DPLS host simulator для lab/replay/Soft-BLE; не исполняет production HEX |
| `mobile/core/` | общий KMP controller, protocol, crypto, domain/session, Compose UI и platform transports |
| `mobile/android/` | Android shell и debug E2E |
| `mobile/ios/` | Xcode shell и минимальный Swift bootstrap |
| `mobile/web/` | тот же Compose UI поверх `LabBleTransport` |
| `docs/` | архитектура, bring-up и технические reference |
| `tools/` | build/flash/check/lab/session utilities |

Production PHY62XX SDK не vendored: точная версия **3.1.2** закреплена в `firmware/sdk/phy6252-sdk.env`.

## BLE identity

Текущая схема identity:

1. firmware спрашивает заводской MAC PHY6252 через vendor `check_chip_mAddr()`;
2. если заводского MAC нет, используется сохранённый SNV MAC;
3. если и его нет, один раз генерируется MAC и сохраняется в SNV;
4. IRK/CSRK аналогично живут в BLE SNV;
5. public BD_ADDR задаётся через `HCI_EXT_SetBDADDRCmd()` до `GAPRole_StartDevice()`.

Отдельного factory record в `0x1103F000` нет. Он не нужен для запуска BLE и не участвует в build/flash path.

Имя в эфире — `Test-DPLS-XXXX`, где `XXXX` берётся из identity MAC. Это discovery hint, не authoritative `NodeId`. Полный device identity подтверждается через `DEVICE_INFO_REPORT` после аутентификации.

Ошибка подготовки identity не блокирует advertising: плата остаётся видимой как `Test-DPLS-0000` вместо того, чтобы исчезнуть из эфира.

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

Реальный GCC target HEX собирается в CI и запускается через [Pom4H/firmverse](https://github.com/Pom4H/firmverse). CI обязан подтвердить, что firmware не только исполняется, но и реально доходит до включения BLE advertising.

## Сборка firmware

```sh
tools/build_firmware.sh keil tmp/test-dpls.hex
tools/build_firmware.sh gcc  tmp/test-dpls-gcc.hex
```

## Прошивка платы

Обычная прошивка не требует Enter:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex
```

Programmer сам посылает ROM handshake `UXTDWU` на 9600 бод и ждёт reset/входа в bootloader. Если control lines стенда не подключены, пользователь только удерживает KEY1 и делает reset/перезапуск питания — подтверждать это в терминале не нужно.

Для стенда/агента с подключёнными RTS/DTR:

```sh
bash tools/flash_firmware_agent.sh tmp/test-dpls.hex
```

Agent path полностью unattended: RTS/DTR используются для ROM-entry, затем идёт тот же `UXTDWU@9600` и обычный `wh`.

Полный chip erase доступен явно:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex --erase
```

Он стирает SNV/bonds, поэтому после erase BLE identity и ключи будут созданы заново обычным boot path.

## Boot order PHY6252

```text
power/reset
  ↓
RAM retention + FS mount
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

Boot journal не имеет права задерживать GAP/advertising. Deferred flash обслуживается без active BLE link.

## BLE/GATT

| Элемент | UUID |
|---|---|
| Service | `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001` |
| RX / WRITE | `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001` |
| TX / INDICATE+NOTIFY | `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001` |

CCCD доступен до SMP, а защищённая protocol boundary — encrypted RX characteristic.

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
