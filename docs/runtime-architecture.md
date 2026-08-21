# Runtime-архитектура RC7

RC7 упрощает ownership: состояние хранится там, где уже существует реальный факт. Не создаём второй state только ради красивой абстракции.

## Общая схема

```text
Телефон
  ↓ BLE callbacks
Android / iOS transport
  ↓ ConnectionEvent
ConnectionActor
  ↓ pure reduce(oldState, event)
ConnectionMachine
  ↓
DeviceSession
  ↓ projection
DplsUiState

================ BLE ================

PHY6252 OSAL event loop
  ├─ RX → dpls_server
  ├─ TX → GATT
  ├─ ADC
  ├─ LED
  └─ STORAGE
        ↓
      реальные очереди RAM
        ↓ только без active link
      SNV / flash
```

## Mobile: один lifecycle

`DeviceSession` — единственное значение, описывающее состояние соединения и application-authentication.

Нормальный путь:

```text
Offline
  ↓ ConnectRequested
Connecting
  ↓ LinkConnected
Discovering
  ↓ Subscribed
Linked
  ↓ ChallengeReceived
Authenticating / Commissioning
  ↓ Authenticated
Synchronizing
  ↓ IdentityVerified
Online
```

`Online` всегда содержит подтверждённый `NodeId`. Успешная аутентификация сама по себе ещё не переводит устройство в `Online`.

`DplsClient` не хранит отдельные `sessionId`, `token`, `authenticated` или другой второй lifecycle. UI-поля `phase`, `authenticated`, `initialized` и `credentialsReady` — только проекция `DeviceSession`.

Поздняя асинхронная работа дополнительно защищена:

- protocol response — `Frame.sequence`;
- operation timeout — `sequence + linkGeneration`;
- reconnect / RSSI / telemetry — `linkGeneration`;
- scan deadline — `scanGeneration`;
- journal timeout — `logTimeoutGeneration`.

## Protocol

Protocol v2 имеет один transaction id — `Frame.sequence`.

```text
request #42
   ↓
firmware
   ↓
response #42
```

Не добавлять второй `commandId` или отдельные correlation-id для того же запроса.

## Firmware safety

`dpls_safety` единолично владеет опасным режимом, его deadline и причинами forced return.

Приложение может попросить режим, но только firmware решает, может ли он оставаться включённым.

Обязательные возвраты в `NORMAL`:

- disconnect;
- отсутствие authenticated activity;
- timeout режима;
- low reserve;
- real short;
- ошибка применения силового режима.

Переключение выходов остаётся break-before-make.

## Firmware storage: без второго state machine

В RC6 существовал отдельный `dpls_storage_actor` со своими `pending`, `link_active` и `phase`. Эти значения дублировали реальные факты и удалены в RC7.

Теперь истина простая:

```text
есть staged SNV?        → dpls_phy6252_snv_pending()
есть journal writeback? → dpls_phy6252_storage_pending()
есть BLE link?          → dpls_phy6252_link_active()
```

`dpls_phy6252_storage.c` только объединяет эти факты:

```text
flash_work_pending = SNV pending || journal pending
```

Правило записи:

```text
active BLE link
    ↓
ждём TX idle
    ↓
disconnect
    ↓
advertising off
    ↓
ровно одна flash operation за OSAL turn
    ↓
если работа осталась — следующий STORAGE event
если закончилась — advertising on
```

SNV во время активного link хранит в RAM одну staged-транзакцию. Это намеренное ограничение RAM PHY6252. Повторная запись того же record обновляет staged-значение; второй record до flush отклоняется.

Journal использует RAM write-behind и физически записывает flash только без активного BLE link.

## PHY6252 workaround'ы, которые не являются legacy

Не удалять ради «чистоты» подтверждённые ограничения платформы:

- RX, TX, ADC и flash работают отдельными OSAL turns;
- HMAC state не кладётся на 1 KiB OSAL stack;
- ADC channels запускаются последовательно;
- один ATT PDU находится in-flight;
- indication считается завершённым только после ATT confirmation;
- Samsung notify path сохраняется;
- blocking SNV временно расширяет watchdog и затем возвращает `WDG_2S`;
- slave connection parameter update остаётся выключен;
- CCCD доступен до encryption, защищённой границей остаётся RX characteristic.

Это не архитектурный мусор, а зафиксированные свойства PHY6252/BLE stack.

## Правило дальнейшего рефакторинга

Новая абстракция допустима только если она хотя бы одно из следующего:

1. удаляет существующий mutable state;
2. делает недопустимое состояние непредставимым;
3. изолирует реальный platform boundary;
4. позволяет тестировать важную policy как pure function.

Если новая сущность просто копирует `pending`, `connected`, `phase` или другой уже существующий факт — её не добавляем.
