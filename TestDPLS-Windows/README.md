# Тест-ДПЛС Windows

Клиент для ноутбука на Windows 10/11 (C# / WPF / .NET 8) для безопасного управления устройством на PHY6252. Протокол и GATT UUID совпадают с Android- и iOS-клиентами и прошивкой.

- Service: `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001`
- RX (WRITE): `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001`
- TX (INDICATE/NOTIFY): `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001`

Приложение фильтрует рекламу по Service UUID, выполняет BLE-сопряжение (bonding), challenge-response аутентификацию (PBKDF2-HMAC-SHA256 + HMAC-SHA256) и считает режим включённым только после `COMMAND_RESULT` и последующего `STATE_REPORT`.

## Требования

- Windows 10 2004+ / Windows 11
- Bluetooth LE (Bluetooth 4.0+)
- [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0)

## Сборка и запуск

```powershell
cd TestDPLS-Windows
dotnet build src\TestDPLS\TestDPLS.csproj -c Release
dotnet run --project src\TestDPLS\TestDPLS.csproj -c Release
```

Публикация self-contained exe:

```powershell
dotnet publish src\TestDPLS\TestDPLS.csproj -c Release -r win-x64 --self-contained true -o artifacts\win-x64
```

## Тесты протокола

Ядро протокола/криптографии — отдельная библиотека `TestDPLS.Core` (без WinRT), тесты проходят и на Linux CI:

```bash
cd TestDPLS-Windows
dotnet test tests/TestDPLS.Core.Tests/TestDPLS.Core.Tests.csproj
```

## Структура

| Путь | Назначение |
|---|---|
| `src/TestDPLS.Core/Protocol/` | Бинарный кодек и CRC16-CCITT-FALSE |
| `src/TestDPLS.Core/Crypto/` | PBKDF2 / HMAC-SHA256 |
| `src/TestDPLS.Core/Models/` | Режимы, состояние UI, журнал |
| `src/TestDPLS.Core/Session/` | Сессия: auth, режимы, журнал, настройки |
| `src/TestDPLS/Ble/BleClient.cs` | Windows BLE: скан, сопряжение, GATT, reconnect |
| `src/TestDPLS/MainWindow.*` | UI: устройства, identify, вход, испытание, журнал, настройки |
| `tests/TestDPLS.Core.Tests/` | Known-answer тесты framing/CRC |

## Сценарии

Те же, что у мобильных клиентов: поиск → «Показать на объекте» (identify LED) → первичная настройка / вход → испытание → журнал → смена имени/пароля.

## Отличия от мобильных клиентов

- Идентификатор в списке — Bluetooth-адрес адаптера Windows (не iOS UUID).
- Сопряжение: `DeviceInformation.Pairing.PairAsync()` + запись в зашифрованную RX-характеристику.
- ATT MTU Windows не отдаёт явно; лимит записи кадров принят 180 байт (достаточно для рукопожатия и журнала).
