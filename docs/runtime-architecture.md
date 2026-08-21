# Runtime-архитектура RC6: события → reducer → state

RC6 фиксирует один источник истины для соединения и отдельные владельцы safety/storage на PHY6252. Здесь нет второго lifecycle, скрытого в UI, transport или telemetry.

## Каноническая схема

```text
                    ┌──────────────┐
 Android / iOS ────▶│ BLE DRIVER   │
                    └──────┬───────┘
                           │ events
                           ▼
                ┌─────────────────────┐
                │ CONNECTION REDUCER  │
                │                     │
                │ Offline             │
                │ Connecting          │
                │ Discovering         │
                │ Securing            │
                │ Authenticating      │
                │ Synchronizing       │
                │ Ready               │
                │ Recovering          │
                └──────────┬──────────┘
                           │ protocol
                           ▼

================================================== BLE

                           ▼
                ┌─────────────────────┐
                │ PHY APP ACTOR       │
                │ ProcessEvent(event) │
                └───────┬───────┬─────┘
                        │       │
                 ┌──────▼───┐ ┌─▼──────────┐
                 │ SAFETY   │ │ STORAGE    │
                 │ REDUCER  │ │ ACTOR      │
                 └──────┬───┘ └────┬───────┘
                        │           │
                     effects     effects
                        │           │
                        ▼           ▼
                      GPIO      FLASH WINDOW
```

Этой схеме соответствуют реальные production owners:

- mobile mutable lifecycle owner — `ConnectionActor`;
- legal state graph — чистый `ConnectionMachine.reduce(state, event)`;
- lifecycle value — `DeviceSession`;
- Android/iOS BLE adapters поставляют факты, но не владеют product lifecycle;
- PHY app actor — `SimpleBLEPeripheral_ProcessEvent`;
- dangerous-mode policy — `dpls_safety`;
- radio/flash ordering — `dpls_storage_actor` через `dpls_phy6252_storage`;
- GPIO и SNV/flash исполняют решения, но не принимают product/safety policy.

Текущие имена типов сохраняют совместимость с RC5:

- `DeviceSession.Linked` = **Securing**;
- `DeviceSession.Online` = **Ready**;
- `Commissioning` — ветка **Authenticating** для первичной настройки;
- `Failed` — терминальное fail-closed состояние вне нормального happy-path графа.

## Mobile lifecycle

`DplsClient` не пишет желаемое следующее состояние. Он преобразует callback/protocol milestone в `ConnectionEvent`:

```text
BLE/platform fact
      ↓
ConnectionEvent
      ↓
ConnectionActor.dispatch(event)
      ↓
ConnectionMachine.reduce(oldState, event)
      ↓
new DeviceSession
      ↓
ConnectionActor.state
      ↓
DplsUiState projection
```

Reducer state-only: он не делает I/O и не возвращает декоративные effects, которые никто не исполняет. Реальный `transport.connect()`, protocol request и UI action остаются в product orchestration рядом с фактом, который их вызвал.

Единственный путь мутации lifecycle — `ConnectionActor.dispatch(ConnectionEvent)`. Compatibility bridge `transitionTo(nextState)` отсутствует.

### Happy path

```text
Offline
  ↓ ConnectRequested
Connecting
  ↓ LinkConnected
Discovering
  ↓ Subscribed
Securing        = DeviceSession.Linked
  ↓ ChallengeReceived
Authenticating  = Authenticating / Commissioning
  ↓ Authenticated
Synchronizing
  ↓ IdentityVerified
Ready           = DeviceSession.Online
```

`Ready` невозможно получить только из факта успешной аутентификации: сначала обязательна проверка `DEVICE_INFO_REPORT` и стабильного `NodeId`.

### Recovery

Recovery определяется только `DeviceSession`, а не cached telemetry, UI phase или наличием ранее полученного `STATE_REPORT`.

- link loss из `Authenticating/Synchronizing/Ready/Recovering` → `Recovering`;
- radio loss во время активной connect/auth попытки → `Recovering`;
- `Failed` не оживает от `BluetoothAvailable/BluetoothUnavailable`;
- `Reset` из любого состояния → `Offline`;
- operator disconnect диспатчит `Reset` **до** platform disconnect, поэтому синхронный callback не может случайно запустить reconnect.

Safety reject команды (`REAL_SHORT`, low reserve и т.п.) не является ошибкой BLE connection. Mobile остаётся в `Ready`, завершает command operation и перечитывает `STATE`, чтобы показать фактический `NORMAL`.

## UI не является истиной

`DplsUiState.phase`, `authenticated`, `initialized`, `credentialsReady` — только projection из `DeviceSession`.

Lifecycle-решение запрещено принимать по:

- `state.phase`;
- `state.authenticated`;
- cached `state.state`;
- `logLoadPending`;
- старому BLE/display name.

Telemetry может влиять на presentation и polling, но не решает, существует ли соединение и надо ли его восстанавливать.

## BLE security

```text
connect
  ↓
service discovery
  ↓
CCCD subscribe в plaintext
  ↓
первый protected RX write
  ↓ GATT insufficient authentication/encryption
SMP pairing / encryption
  ↓
повтор blocked RX frame
  ↓
protocol auth
```

RX — encrypted security boundary. CCCD намеренно доступен до encryption для Android/CoreBluetooth compatibility.

Android GATT 5/15 — security transition. Android/iOS transport владеют platform security mechanics, но не вторым product connection deadline и не product lifecycle.

Delayed mobile work защищено identity:

- protocol operation → `Frame.sequence` + `linkGeneration`;
- reconnect/RSSI/session loop → `linkGeneration`;
- scan → `scanGeneration`;
- journal timeout → `logTimeoutGeneration`.

## Protocol transaction ownership

`DplsProtocol.Frame.sequence` — единственный transaction id. `DplsWire` допускает одну stop-and-wait product transaction одновременно.

Не добавлять второй `commandId`, `awaitingFoo`, `fooPending` или generic broker без реальной потребности в параллельных protocol transactions.

## PHY6252 app actor

`SimpleBLEPeripheral_ProcessEvent` сериализует target runtime events:

- GAP connect/disconnect;
- GATT confirmation;
- RX/TX;
- ADC completion;
- tick;
- storage event;
- LED event.

RX callback не выполняет domain work и не прокачивает TX. ATT confirmation timeout закрывает физический link и никогда не подделывает `TX_CONFIRMED`.

## Safety reducer

`dpls_safety` — единственный владелец:

- dangerous mode;
- mode deadline;
- revision;
- forced-return priority.

Safety reducer не зависит от GAP/GATT/OSAL. GPIO adapter только применяет решение.

Ключевые fail-safe переходы в `NORMAL`:

- disconnect;
- потеря валидных safety measurements;
- real short;
- reserve low;
- dangerous-mode deadline;
- authenticated activity timeout.

Ошибка `apply_mode()` также сводит physical outputs и logical safety state к `NORMAL`, чтобы не было split-brain.

## Storage actor и flash window

Storage actor владеет полным radio/flash порядком:

```text
RADIO
  ↓ WRITE_REQUESTED
DRAINING
  ↓ TX idle
controlled disconnect
  ↓ LINK_DOWN
FLASH
  ↓ bounded commit
RADIO
  ↓
advertising enabled
```

Инварианты:

- blocking SNV запрещён при active BLE link;
- disconnect для flash только после `dpls_phy6252_tx_idle()`;
- advertising выключен на весь flash window;
- один blocking flash unit за storage turn;
- watchdog расширяется только вокруг physical SNV write;
- target shell видит один storage facade и не выбирает отдельно settings/auth/journal persistence policy.

Firmware journal использует RAM write-behind и физически коммитится тем же storage path.

## Identity

`NodeId` и BLE route — разные факты.

- `NodeId` — подтверждённый 32-bit `serial_number`;
- `LinkEndpoint.Ble` — текущий route;
- `Test-DPLS-XXXX` содержит только младшие 16 бит serial и не является identity;
- `Ready/Online` всегда содержит подтверждённый ненулевой `NodeId`.

До первого `DEVICE_INFO_REPORT` verifier может временно храниться по endpoint, затем канонический ключ — `node:<NodeId>`.

## Запрещённые архитектурные возвраты

Изменение следует отклонить, если оно:

- создаёт второй mutable owner lifecycle/auth;
- добавляет прямой `transitionTo(nextState)` в обход semantic event;
- делает `Ready` возможным без identity proof;
- решает reconnect по UI/telemetry cache;
- превращает safety command reject в connection failure;
- добавляет второй transaction id;
- позволяет GPIO принимать safety policy;
- позволяет PHY target напрямую выбирать SNV/journal flash policy;
- выполняет blocking flash при active link/advertising;
- считает timeout успешным ATT confirmation.

`tools/architecture_guard.py`, reducer matrix tests, Soft-BLE E2E и production CI фиксируют эти границы.
