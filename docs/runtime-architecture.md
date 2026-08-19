# Runtime-архитектура: один источник истины и сериализованные эффекты

Абстракция полезна здесь только если она убирает дублируемое mutable state, делает недопустимое состояние непредставимым или задаёт реальную границу, уже используемую продуктом.

## Зоны зависимостей

```text
:wire      frame / CRC / crypto / radio-name helpers; без coroutines, UI и OS API
   ↓
:runtime   NodeId, BLE endpoint, lifecycle сессии, frame sequencing
   ↓
:core      product orchestration, journal, Compose, platform adapters
```

`core` может зависеть вниз. `wire` и `runtime` не должны зависеть от Compose, Android Bluetooth, CoreBluetooth или экранов приложения.

Не проектируем заранее mesh, RS-232, routing и passive observations. Эти границы появятся только вместе с реальной фичей.

## Инвариант identity

Identity прибора и способ текущего подключения — разные факты.

- `NodeId` — стабильный 32-битный `serial_number`, подтверждённый `DEVICE_INFO_REPORT`.
- `LinkEndpoint.Ble` — текущий маршрут до прибора.
- `Test-DPLS-XXXX` — radio/display name; `XXXX` содержит только младшие 16 бит serial.
- `DeviceSession.Online` всегда содержит подтверждённый ненулевой `NodeId`.

BLE-имя **не создаёт `candidateNodeId`**: 16 бит недостаточно для проверки 32-битного serial. До `DEVICE_INFO_REPORT` полный identity неизвестен.

```text
Connecting
    ↓
Discovering
    ↓
Linked
    ↓
Authenticating / Commissioning
    ↓
Synchronizing       auth уже есть, NodeId ещё не подтверждён
    ↓ DEVICE_INFO_REPORT
Online              auth + подтверждённый NodeId
```

После `DEVICE_INFO_REPORT` текущая сессия связывается с `NodeId`; дальнейшая смена ID внутри активной сессии считается ошибкой и закрывается fail-closed.

### Хранение verifier

Канонический долговременный ключ verifier — `node:<NodeId>`.

До первого `DEVICE_INFO_REPORT` verifier может временно храниться под `endpoint:<BLE endpoint>`, чтобы пережить reboot PHY6252 после первичной настройки. Это bootstrap cache, а не identity. После подтверждения NodeId тот же verifier сохраняется под `node:<serial>`.

Старые alias `id:`, `addr:` и `legacy-addr:` намеренно не читаются и не записываются: до выхода в серию migration compatibility не поддерживается.

## Инвариант запросов — protocol v2

`DplsProtocol.Frame.sequence` — единственный transaction id. Флаги `REQUEST/RESPONSE/EVENT/ERROR` описывают корреляцию независимо от типа сообщения. Ответ или ошибка повторяет `sequence` запроса.

Controller допускает одну transactional `Operation` одновременно. Не добавлять отдельные `commandId`, `awaitingFoo`, `fooPending` или generic request broker без реальной потребности в параллельных транзакциях.

Таймаут хранит тот же `sequence`: даже если отменённый coroutine уже стал runnable, он может изменить состояние только если его sequence всё ещё принадлежит текущей операции.

## Инвариант конкурентности

Вместо множества mutex проект сериализует изменение продуктового состояния.

Production `DplsClient` работает на `Dispatchers.Main`:

- Android доставляет GATT/product callbacks через main `Handler`;
- iOS создаёт `CBCentralManager` на main queue;
- Compose actions и controller timers наблюдают тот же последовательный state.

Один thread не устраняет позднюю асинхронную работу, поэтому delayed effects дополнительно проверяют identity:

- protocol response → `Frame.sequence`;
- operation timeout → sequence операции;
- connection/session/RSSI/reconnect → `linkGeneration`;
- scan deadline → `scanGeneration`;
- journal timeout → `logTimeoutGeneration`.

Поздний callback может выполниться, но не может мутировать новую логическую операцию.

## Инвариант сессии

`DeviceSession` — единственный mutable source of truth для lifecycle/auth/identity.

Он содержит:

- endpoint;
- pre-auth client nonce;
- challenge `sessionId`, device nonce и auth salt;
- authenticated session id/token/salt;
- фазу `Synchronizing` до получения identity;
- подтверждённый `NodeId` в `Online`;
- recovering/failed состояния.

`FrameSequencer` хранит только следующий protocol-v2 sequence и не должен обрастать auth/lifecycle state.

`DplsUiState.phase`, `authenticated`, `initialized`, `credentialsReady` — только проекция для UI. `DplsClient` не использует её как протокольную истину.

```text
transport / decoded frame
          ↓
     DeviceSession        ← authoritative lifecycle/auth/identity
          ↓
       DplsClient
          ↓
     projectSession()
          ↓
      DplsUiState         ← presentation snapshot
          ↓
        Compose
```

Обратная зависимость запрещена.

## Инвариант журнала

`JournalMachine` владеет paging/index state и возвращает явные effects (`Ack`, `Pause`, `Complete`, `Error`). Он ничего не знает о BLE, Compose, notifications или coroutine jobs.

Таймауты журнала остаются в `DplsClient` и защищены generation token.

## Инвариант измерений

Validity кодируется самим значением:

- `null` — достоверного измерения нет;
- `0` — достоверные ноль вольт.

Не добавлять пары `fooValue + fooValid`. Capability bits остаются внутри `DeviceCapabilities`.

## Firmware safety

`dpls_safety` — единственный владелец dangerous-mode state, deadline math, revision и приоритетов forced return. BLE, journal export, authentication proof и calendar time туда не добавляются.

Если `hal.hardware.apply_mode()` завершается ошибкой, firmware переводит и физические выходы, и logical safety state в `NORMAL`, исключая split-brain.

## Будущие mesh и RS-232

`NodeId` уже независим от BLE endpoint — этого достаточно для будущего routing. `PacketRouter`, `ByteLink`, serial endpoint и topology types должны появляться только в PR, который реально ими пользуется.

## Правила удаления

Изменение следует отклонить, если оно без удаления эквивалентного state добавляет:

- второго владельца session/auth;
- nullable identity внутри `Online`;
- второй transaction id;
- controller decisions на основе UI lifecycle projections;
- delayed action без sequence/generation guard;
- `awaitingX` / `xPending` orchestration flags;
- UI-строки в wire/domain enums;
- пары `value + valid`;
- generic manager/repository/use-case interface с одной реализацией;
- speculative mesh/serial abstractions без caller.

`tools/architecture_guard.py` — узкий migration tripwire, а не численная метрика качества архитектуры. Основная защита — типы, направления зависимостей и behavioral tests.
