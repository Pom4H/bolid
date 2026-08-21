# Runtime-архитектура RC7

RC7 хранит состояние там, где существует реальный факт. Не создаём второй state ради абстракции.

## Общая схема

```text
Телефон
  ↓ BLE callbacks
Android / iOS transport
  ↓ ConnectionEvent
DplsClient.dispatchConnection()
  ↓
ConnectionMachine.reduce(oldState, event)
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
  └─ STORAGE → RAM queues → SNV/flash только без active link
```

## Mobile: один lifecycle

`DplsClient.session: DeviceSession` — единственное mutable lifecycle-значение. Записывать его можно только так:

```kotlin
session = ConnectionMachine.reduce(session, event)
```

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
Securing
  ↓ Authenticated
Synchronizing
  ↓ IdentityVerified
Online
```

`Securing.challenge.initialized` уже говорит, нужна первичная настройка или обычная аутентификация. Поэтому отдельные `Commissioning` и `Authenticating` удалены.

`Online` всегда содержит подтверждённый `NodeId`. Успешная аутентификация сама по себе ещё не переводит устройство в `Online`.

UI-поля `phase`, `authenticated`, `initialized` и `credentialsReady` — только проекция `DeviceSession`.

Поздняя работа защищена identity своей попытки:

- protocol response — `Frame.sequence`;
- operation timeout — `sequence + linkGeneration`;
- reconnect / RSSI / telemetry — `linkGeneration`;
- scan deadline — `scanGeneration`;
- journal timeout — `logTimeoutGeneration`.

## Protocol

Protocol v2 имеет один transaction id — `Frame.sequence`. Старые `commandId` и v1 layouts удалены.

## Firmware safety

`dpls_safety` единолично владеет опасным режимом, deadline и forced return.

Возврат в `NORMAL` обязателен при:

- disconnect;
- отсутствии authenticated activity;
- timeout режима;
- low reserve;
- real short;
- ошибке применения силового режима.

Переключение выходов остаётся break-before-make.

## Firmware storage: без второго state machine

Истина берётся из реальных очередей:

```text
staged SNV?        → dpls_phy6252_snv_pending()
journal writeback? → dpls_phy6252_storage_pending()
BLE link?          → dpls_phy6252_link_active()
```

`dpls_phy6252_storage.c` только объединяет эти факты:

```text
flash_work_pending = SNV pending || journal pending
```

Порядок записи:

```text
active BLE link
    ↓
TX idle
    ↓
disconnect
    ↓
advertising off
    ↓
одна flash operation за OSAL turn
    ↓
ещё есть работа? → следующий STORAGE event
нет работы?      → advertising on
```

SNV во время active link держит одну staged-транзакцию в RAM. Повторная запись того же record обновляет её; другой record до flush отклоняется.

## PHY6252 workaround'ы, которые не являются legacy

Не удалять подтверждённые ограничения платформы:

- RX, TX, ADC и flash выполняются отдельными OSAL turns;
- HMAC state не кладётся на 1 KiB OSAL stack;
- ADC channels запускаются последовательно;
- одновременно in-flight только один ATT PDU;
- indication завершается только после ATT confirmation;
- Samsung notify path сохраняется;
- blocking SNV временно расширяет watchdog и возвращает `WDG_2S`;
- slave connection parameter update выключен;
- CCCD доступен до encryption, защищённой границей остаётся RX characteristic.

## Правило дальнейшего рефакторинга

Новая сущность допустима только если она:

1. удаляет mutable state;
2. делает недопустимое состояние непредставимым;
3. изолирует реальную platform boundary; или
4. позволяет тестировать важную policy как pure function.

Если сущность просто копирует `pending`, `connected`, `phase` или другой существующий факт — её не добавляем.
