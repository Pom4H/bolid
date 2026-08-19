# Архитектура Test-DPLS

Этот документ фиксирует границы ответственности в проекте. Перенос ответственности через такую границу — архитектурное изменение, а не локальный рефакторинг.

## Модель выполнения

```text
┌──────────────────────────────────────────────────────────────┐
│ firmware/                                                    │
│ переносимый C99-сервер + адаптер PHY6252                     │
│ safety · persistence · outputs · ATT TX                      │
└──────────────────────────────┬───────────────────────────────┘
                               │ бинарный протокол Test-DPLS v2
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ mobile/core/src/commonMain                                   │
│ DplsClient — единственный application controller             │
│ wire/auth · domain/session · reconnect · settings · journal  │
│ общий Compose UI DplsApp                                     │
└──────────────────────────────┬───────────────────────────────┘
                               │ DplsTransport + platform services
                 ┌─────────────┴─────────────┐
                 │                           │
        ┌────────▼──────────┐       ┌────────▼──────────┐
        │ core/androidMain │       │ core/iosMain      │
        │ Android BLE      │       │ CoreBluetooth     │
        │ platform effects │       │ platform effects  │
        └────────┬─────────┘       └────────┬──────────┘
                 │                           │
        ┌────────▼─────────┐       ┌────────▼──────────┐
        │ mobile/android/  │       │ mobile/ios/       │
        │ Android shell    │       │ Xcode shell       │
        └──────────────────┘       └───────────────────┘
```

Android и iOS используют один `DplsClient` и один `DplsApp`. Platform-код переводит системные BLE/lifecycle события в общий контракт; бизнес-логика и протокол не дублируются.

## Firmware владеет безопасностью

Только прошивка решает, может ли электрический тестовый режим оставаться включённым. Мобильное приложение запрашивает действие, но не является safety boundary.

Firmware отвечает за:

- безопасный `NORMAL` при старте, disconnect и внутренней ошибке;
- break-before-make переключение силовых выходов;
- таймауты опасного режима и сессии;
- приоритет низкого резерва и реального КЗ над командой оператора;
- физические ADC/readback и board mapping;
- настройки, authentication lock и журнал;
- сериализацию ATT TX и fail-safe по таймауту подтверждения;
- серийную factory identity PHY6252.

Краш приложения или потеря BLE не должны оставлять опасный выход включённым.

## `commonMain` владеет поведением продукта

Код должен находиться в `mobile/core/src/commonMain`, если Android и iOS обязаны дать одинаковый результат.

Здесь находятся:

- `DplsClient` — единственный controller/session orchestrator;
- wire framing, CRC-16/CCITT-FALSE, encode/decode;
- SHA-256/HMAC/PBKDF2 и application authentication;
- parsers `AUTH_*`, `COMMAND_RESULT`, `STATE_REPORT`, `DEVICE_INFO_REPORT`, settings и journal;
- общие domain/session типы;
- reconnect, keepalive, state refresh, команды, настройки и журнал;
- Compose UI `DplsApp`.

`commonMain` не должен импортировать Android framework, CoreBluetooth, UIKit и другие platform-only API.

Wasm-клиент (`mobile/web`) использует тот же `DplsApp` и тот же продуктовый controller. WebSocket к `dpls_simulator` — только другой transport.

## Platform-код

Android (`core/src/androidMain`) отвечает за `BluetoothGatt`, bonding, MTU, CCCD, retries, stale-bond/GATT recovery, foreground keep-alive и локальные уведомления. `mobile/android` содержит только Android shell: permissions, Activity/Application и debug E2E driver.

iOS (`core/src/iosMain`) отвечает за `CBCentralManager`/`CBPeripheral`, очередь write, CoreBluetooth lifecycle, Apple random/clock и локальные уведомления. `mobile/ios` содержит Xcode project, signing, plist/assets и минимальный Swift bootstrap.

Platform adapters не должны создавать второй codec, parser, session controller или UI.

## Identity: один источник истины

В production-схеме устройства есть три разных факта:

- `serial_number` / `NodeId` — постоянная 32-битная identity прибора;
- BLE address / `CBPeripheral.identifier` — транспортный endpoint;
- `Test-DPLS-XXXX` — имя для поиска и отображения, где `XXXX` — только младшие 16 бит serial.

Полный `NodeId` становится достоверным только после `DEVICE_INFO_REPORT`. Суффикс BLE-имени **не является** `NodeId` и не используется для проверки identity.

Factory identity обязательна: без валидного record прошивка не начинает advertising. Подробности: [factory-identity.md](factory-identity.md).

## Advertising и discovery

Текущий эфирный контракт намеренно минимален:

- стандартные BLE flags;
- фирменный 128-bit Service UUID;
- local name `Test-DPLS-XXXX`.

Manufacturer Specific Data и Company ID `0x0B01` отсутствуют. Firmware version, hardware revision, capabilities, полный serial и пользовательское имя читаются через `DEVICE_INFO_REPORT` после подключения.

## Истина для команд

Успешный GATT write не доказывает, что железо перешло в требуемый режим.

```text
оператор
  → DplsClient выдаёт sequence
  → GATT write
  → firmware проверяет и применяет/отклоняет
  → COMMAND_RESULT с тем же sequence
  → STATE_REPORT
  → UI показывает подтверждённое состояние
```

Поздний ответ с другим `sequence` игнорируется. `STATE_REPORT` — источник истины для фактического состояния оборудования.

## Направление зависимостей

Разрешено:

```text
mobile/android shell       → core/androidMain + commonMain
core/androidMain           → commonMain
core/iosMain               → commonMain
mobile/ios bootstrap       → DplsCore
firmware target adapter    → pinned PHY62XX SDK
CI production HEX          → Pom4H/firmverse@v1
```

Запрещено:

```text
commonMain → Android/CoreBluetooth/UIKit
portable firmware/src → vendor SDK headers
platform code → duplicate protocol/controller/UI
mobile app → hardware safety decisions
Bolid repo → standalone PHY6252/ZMU/guest HEX emulator
```

## Где должны жить тесты

- firmware behavior → host firmware tests;
- wire/CRC/auth/parsers → KMP common tests;
- session/application behavior → `DplsClient` fake-transport tests;
- Android framework → lint/build + hardware E2E;
- iOS integration → Kotlin/Native + XCTest smoke;
- host product path → `tools/soft_ble_e2e.sh`;
- wasm/laptop BLE → `tools/dpls_lab.sh`;
- **production PHY6252 HEX execution → Firmverse GitHub Action**;
- реальный radio/pairing/силовые выходы → hardware bring-up.

`tools/check_repo_layout.sh` защищает проект от возврата дублирующих controllers, protocol facades, UI-деревьев и локального PHY6252 emulator stack.

## Правило разработки

Обычная продуктовая фича должна в первую очередь менять одну общую Kotlin-область, а не параллельные реализации Android и Swift.

- экран/application flow → `mobile/core/src/commonMain/.../app/`;
- protocol/auth → `mobile/core/src/commonMain/.../protocol/`;
- session runtime → `mobile/runtime/` и общий core;
- Android BLE quirk → `core/src/androidMain/`;
- iOS BLE quirk → `core/src/iosMain/`;
- OS shell → `mobile/android/` или `mobile/ios/`.

Для mobile loop используется `bash tools/check_mobile.sh`, для host-side gates — `bash tools/check_all.sh`.

PHY62XX SDK закреплён отдельно в `firmware/sdk/phy6252-sdk.env`; его обновление считается отдельной миграцией.

## Эмуляция

Быстрый product simulator целиком живёт в `firmware/sim/`. Его private transport моделирует только ATT queue/pacing для lab, replay и Soft-BLE. Он **не** исполняет Intel HEX, не моделирует Cortex-M0/MMIO/vendor ROM и не является PHY6252 acceptance gate.

Production HEX исполняется только внешним [Firmverse](https://github.com/Pom4H/firmverse) через `Pom4H/firmverse@v1`. Standalone `firmware/phy6252_emu/`, `firmware/zmu/`, `tools/zmu_*` и `third_party/phy6252-emu` удалены из Bolid. Подробнее: [chip-emulator.md](chip-emulator.md).
