# Runtime-архитектура RC6: события → reducer → effects

Абстракция полезна здесь только если она убирает дублируемое mutable state, делает недопустимое состояние непредставимым или задаёт реальную границу, уже используемую продуктом.

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
                           │ protocol effects
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

Это не схема «на будущее». В RC6 ей соответствуют реальные owners:

- mobile mutable lifecycle owner — `ConnectionActor`;
- переходы определяет чистый `ConnectionMachine.reduce(state, event)`;
- значение lifecycle — `DeviceSession`;
- PHY6252 app actor — единственный OSAL dispatcher `SimpleBLEPeripheral_ProcessEvent`;
- dangerous-mode policy — `dpls_safety`;
- radio/flash ordering — `dpls_storage_actor` через `dpls_phy6252_storage`;
- GPIO и SNV/flash выполняют effects, но не принимают продуктовые решения.

Текущие имена типов сохраняют совместимость с RC5: `DeviceSession.Linked` соответствует фазе **Securing**, `DeviceSession.Online` — **Ready**. `Commissioning` — ветка `Authenticating` для первичной настройки, `Failed` — терминальное fail-closed состояние. Это не дополнительные владельцы lifecycle.

## Зоны зависимостей

```text
:wire      frame / CRC / crypto / radio-name helpers; без coroutines, UI и OS API
   ↓
:runtime   NodeId, BLE endpoint, DeviceSession, ConnectionMachine, frame sequencing
   ↓
:core      ConnectionActor, product orchestration, journal, platform adapters, UI
```

`core` может зависеть вниз. `wire` и `runtime` не должны зависеть от Compose, Android Bluetooth, CoreBluetooth или экранов приложения.

## Mobile: один owner lifecycle

`ConnectionActor.state` — единственное mutable lifecycle-состояние. `DplsClient` получает его только как read-only `session` projection и не хранит вторую mutable копию.

```text
BLE/platform fact
      ↓
ConnectionEvent
      ↓
ConnectionMachine.reduce(oldState, event)
      ↓
ConnectionTransition
   ┌───────┴───────┐
 new DeviceSession effects
                     ↓
               protocol/link action
```

Reducer является total function: тест проходит по матрице `state × event`. Невозможное или позднее событие не должно создавать новый допустимый lifecycle из воздуха; `Ready/Online` достигается только после `IdentityVerified` из `Synchronizing`.

### Lifecycle

```text
Offline
  ↓ ConnectRequested
Connecting
  ↓ LinkConnected
Discovering
  ↓ Subscribed
Securing        = DeviceSession.Linked
  ↓ AUTH_CHALLENGE
Authenticating  = Authenticating / Commissioning
  ↓ AUTH_RESULT OK
Synchronizing
  ↓ STATE_REPORT + DEVICE_INFO_REPORT / IdentityVerified
Ready           = DeviceSession.Online
```

Потеря связи из восстановимого состояния переводит только в `Recovering`. Ошибка identity закрывается fail-closed. UI-поля `phase`, `authenticated`, `initialized`, `credentialsReady` — только projection и никогда не являются входом для lifecycle-решения.

## BLE security

GATT security contract единый для Android и iOS:

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

RX остаётся encrypted boundary; CCCD намеренно доступен до encryption для совместимости с CoreBluetooth. Android GATT 5/15 — событие security transition, а не обычная ошибка write. Transport не владеет вторым pairing deadline: продуктовый connection deadline находится в `DplsClient`, firmware имеет только более длинный defensive plaintext deadline.

Delayed mobile work дополнительно защищено identity:

- protocol operation → `Frame.sequence` + `linkGeneration`;
- reconnect/RSSI/session loop → `linkGeneration`;
- scan → `scanGeneration`;
- journal timeout → `logTimeoutGeneration`.

## Identity

Identity прибора и маршрут подключения — разные факты.

- `NodeId` — стабильный 32-битный `serial_number`, подтверждённый `DEVICE_INFO_REPORT`.
- `LinkEndpoint.Ble` — текущий маршрут.
- `Test-DPLS-XXXX` содержит только младшие 16 бит serial и не является identity.
- `Ready/DeviceSession.Online` всегда содержит подтверждённый ненулевой `NodeId`.

До первого `DEVICE_INFO_REPORT` verifier может временно храниться под `endpoint:<BLE endpoint>`, чтобы пережить reboot после первичной настройки. Канонический долговременный ключ — `node:<NodeId>`.

## Protocol transaction identity

`DplsProtocol.Frame.sequence` — единственный transaction id. Ответ или ошибка повторяет `sequence` запроса.

Controller допускает одну transactional `Operation` одновременно. Не добавлять `commandId`, `awaitingFoo`, `fooPending` или generic request broker без реальной потребности в параллельных транзакциях.

Таймаут проверяет одновременно physical-link generation и sequence операции. Отменённый coroutine, уже попавший в runnable queue, не может мутировать новую операцию.

## PHY6252: один app actor

PHY6252 уже имеет actor-модель на уровне OSAL: `SimpleBLEPeripheral_ProcessEvent` — единственное место, которое сериализует внешние события target runtime.

Он принимает:

- GAP connect/disconnect;
- GATT confirmation;
- RX;
- TX;
- ADC completion;
- tick;
- storage event;
- LED event.

RX callback не выполняет domain work и не прокачивает TX. Он только ставит данные/событие; обработка идёт в OSAL turn. ATT timeout никогда не превращается в fake `TX_CONFIRMED`.

## Safety reducer

`dpls_safety` — единственный владелец:

- dangerous mode;
- mode deadline;
- revision;
- приоритета forced return.

Reducer не зависит от GAP/GATT/OSAL. Он получает safety inputs и возвращает решение. GPIO adapter только применяет effect.

Ключевые fail-safe правила:

- disconnect → `NORMAL`;
- потеря валидных safety measurements в dangerous mode → `NORMAL`;
- real short → `NORMAL`;
- reserve low → `NORMAL`;
- dangerous-mode deadline → `NORMAL`;
- authenticated activity timeout → `NORMAL`.

Если `apply_mode()` завершается ошибкой, физические выходы и logical safety state сходятся в `NORMAL`, не оставляя split-brain.

## Storage actor и flash window

Flash нельзя считать безопасным только потому, что «сейчас нет handle». Storage actor владеет полным порядком:

```text
RADIO
  ↓ WRITE_REQUESTED
DRAINING
  ↓ TX idle
controlled disconnect
  ↓ LINK_DOWN
FLASH
  ↓ commit one bounded unit
RADIO
  ↓
advertising enabled
```

Один staged SNV write хранит bytes; actor хранит phase. Target shell не знает, пришла запись из settings/auth или journal — он видит только `dpls_phy6252_storage` facade.

Инварианты:

- blocking SNV невозможен при active BLE link;
- disconnect для flash разрешён только после `dpls_phy6252_tx_idle()`;
- advertising выключен на время flash window;
- один blocking flash unit за storage turn;
- watchdog расширяется только вокруг физического SNV write и сразу возвращается к normal budget;
- новый connect не может вклиниться между disconnect и commit.

## Journal

`JournalMachine` на mobile владеет paging/index state и возвращает `Ack`, `Pause`, `Complete`, `Error` effects.

Firmware journal использует RAM write-behind. Физический journal commit проходит только через storage event при link down. Журнал не создаёт альтернативного flash owner.

## Измерения

Validity кодируется значением на mobile:

- `null` — достоверного измерения нет;
- `0` — достоверный ноль вольт.

Не добавлять пары `fooValue + fooValid` в product model. Firmware safety отдельно использует validity mask как доказательство пригодности измерений для опасного режима.

## Правила для следующих изменений

Изменение следует отклонить, если оно:

- создаёт второй mutable owner lifecycle/auth;
- обходит `ConnectionMachine` прямой записью lifecycle state;
- делает `Ready/Online` возможным без identity proof;
- добавляет второй transaction id;
- принимает lifecycle-решение по UI projection;
- добавляет delayed effect без sequence/generation identity;
- позволяет GPIO принимать safety policy;
- позволяет target shell напрямую выбирать SNV/journal flash policy;
- выполняет blocking flash при active link или advertising;
- считает timeout успешным подтверждением;
- добавляет generic manager/repository/use-case только ради слоя.

`tools/architecture_guard.py` фиксирует эти ownership boundaries. Behavioral tests и production CI остаются основной проверкой корректности.
