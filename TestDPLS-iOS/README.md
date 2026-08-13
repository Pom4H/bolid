# Тест-ДПЛС iOS 1.2.0

iPhone-клиент (Swift + SwiftUI, iOS 16+) для безопасного управления устройством на PHY6252. Протокол и GATT UUID совпадают с Android-клиентом (`TestDPLS/`) и прошивкой.

- Service: `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001`
- RX (WRITE): `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001`
- TX (INDICATE/NOTIFY): `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001`

Приложение фильтрует рекламу по Service UUID, инициирует шифрованное BLE-сопряжение (системный диалог iOS при записи в защищённую характеристику), выполняет challenge-response аутентификацию (PBKDF2-HMAC-SHA256 + HMAC-SHA256) и считает режим включённым только после `COMMAND_RESULT` и последующего `STATE_REPORT`.

## Сборка и запуск

Нужны macOS и Xcode 15+.

```bash
open TestDPLS-iOS/TestDPLS.xcodeproj
```

1. Выберите team в Signing & Capabilities (`ru.bolid.testdpls`).
2. Подключите iPhone (Bluetooth на симуляторе ограничен).
3. Run (⌘R) или тесты протокола: `⌘U` / схему `TestDPLS`.

```bash
xcodebuild -project TestDPLS-iOS/TestDPLS.xcodeproj \
  -scheme TestDPLS -destination 'platform=iOS Simulator,name=iPhone 16' \
  test
```

## Структура

| Путь | Назначение |
|---|---|
| `TestDPLS/Protocol/DplsProtocol.swift` | Бинарный кодек и CRC16-CCITT-FALSE |
| `TestDPLS/BLE/BleClient.swift` | Скан, сопряжение, GATT, аутентификация, журнал, reconnect |
| `TestDPLS/BLE/DplsModels.swift` | Режимы, состояние UI, журнал |
| `TestDPLS/BLE/DplsCrypto.swift` | PBKDF2 / HMAC-SHA256 |
| `TestDPLS/UI/` | Экраны: устройства, identify, вход, испытание, журнал, настройки |
| `TestDPLSTests/` | Known-answer тесты framing/CRC (совместимы с Android/firmware) |

## Отличия от Android

- Идентификатор устройства в списке — UUID CoreBluetooth (`CBPeripheral.identifier`), не MAC (на iOS недоступен).
- ATT MTU согласовывает стек iOS; лимит записи берётся из `maximumWriteValueLength(for: .withResponse)`. Кадры рукопожатия ≤57 байт; при слишком малом MTU операция завершится ошибкой (фрагментация в прошивке пока отложена).
- Сопряжение — системный диалог iOS при первой записи в зашифрованную RX-характеристику.

## Сценарии

Те же, что у Android: поиск → «Показать на объекте» (identify LED) → первичная настройка / вход → испытание → журнал → смена имени/пароля.
