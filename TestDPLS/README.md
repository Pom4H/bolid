# Тест-ДПЛС Android 1.2.0

Тестовое Android-приложение (Kotlin + Jetpack Compose, minSdk 33) для безопасного управления устройством на PHY6252.

- Service: `7b5f1000-5d7a-4d2f-9a4c-14b7d5f00001`
- RX (WRITE): `7b5f1001-5d7a-4d2f-9a4c-14b7d5f00001`
- TX (INDICATE/NOTIFY): `7b5f1002-5d7a-4d2f-9a4c-14b7d5f00001`

Приложение фильтрует рекламу по Service UUID, создаёт зашифрованное BLE-сопряжение, выполняет прикладную challenge-response аутентификацию и считает режим включённым только после `COMMAND_RESULT` и последующего `STATE_REPORT`.

## Сборка и запуск

```bash
./gradlew installDebug
~/Library/Android/sdk/platform-tools/adb shell am start -n ru.bolid.testdpls/.MainActivity
```

Логи — в Logcat по тегу `TestDplsBle`.

## Структура

- `protocol/DplsProtocol.kt` — бинарный кодек и CRC16-CCITT
- `ble/BleClient.kt` — сканирование, сопряжение, GATT, аутентификация, подтверждение команд/состояния и переподключение
- `ui/DplsScreen.kt` — поиск, настройка/вход, управление и журнал
