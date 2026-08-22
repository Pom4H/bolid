# Runtime-архитектура RC8

RC8 уменьшает количество мест, где может находиться истина. Production-код должен читаться сверху вниз за один проход: факт принадлежит одному владельцу, физический ресурс — одному адаптеру, переход состояния — одному reducer/policy.

## Карта кода

```text
mobile/runtime
  DeviceSession.kt          — тип состояния соединения
  ConnectionMachine.kt      — единственный reducer lifecycle

mobile/core/app
  DplsClient.kt             — orchestration продукта
  DplsTransport.kt          — platform boundary
  AndroidBleTransport.kt    — Android Core BLE
  IosBleTransport.kt        — iOS CoreBluetooth

firmware/src
  dpls_protocol.c            — wire format / CRC
  dpls_server.c              — domain session/auth/commands/journal API
  dpls_safety.c              — pure safety policy
  dpls_led.c                 — pure LED scenes
  dpls_calib.c               — calibration math
  dpls_durable_settings.c    — byte-defined dual-slot settings record

firmware/phy6252
  dpls_ble_identity.c        — chip public MAC + optional DID1 serial
  dpls_gatt_service.c        — RX/TX characteristics + CCCD
  dpls_phy6252_auth.c        — TRNG/HMAC
  dpls_phy6252_transport.c   — единственный владелец BLE link/RX/TX
  dpls_phy6252_storage.c     — единственный app-owned SNV writer
  dpls_phy6252_measurements.c— ADC / power facts
  dpls_phy6252_outputs.c     — GPIO / LED actuator, без копии logical mode
  dpls_phy6252_supervisor.c  — watchdog boundary
  dpls_phy6252_runtime.c     — orchestration физических owners

firmware/targets/phy6252/source/dplsBLEPeripheral.c
  — тонкий vendor GAP/OSAL shell
```

Старого `dpls_phy6252_app.c` нет. Второго storage facade нет. Второго BLE lifecycle нет. Target shell не хранит `link_up`, storage не хранит `link_active`, outputs не хранит копию logical mode.

## Кто владеет фактами

| Факт / ресурс | Единственный владелец |
| --- | --- |
| Физическое BLE-соединение | `dpls_phy6252_transport.connection_handle` |
| Lifecycle приложения | `DeviceSession` + `ConnectionMachine.reduce()` |
| Опасный logical mode | `dpls_safety_t.mode` |
| Физические силовые GPIO | `dpls_phy6252_outputs.c` |
| Measurement validity / voltage / power | `dpls_phy6252_measurements.c` |
| App-owned SNV writes | `dpls_phy6252_storage.c` |
| Решение о physical disconnect | `dpls_phy6252_runtime.c` |
| Bond erase | physical factory-reset path |
| Journal sequence/count | domain + storage ring metadata |

## BLE transaction

Protocol v2 — request/response, поэтому transport не изображает message broker.

```text
GATT write
  ↓ runtime admission
1 RX slot
  ↓
DPLS_PHY6252_RX_EVT
  ↓
dpls_server_receive()
  ↓
ровно один response
  ↓
2 TX slots = in-flight + один следующий response
  ↓
ATT indication/notification
```

До того как GATT принимает request, transport резервирует место под его будущий response. Поэтому состояние «команда выполнена, но ACK некуда положить» архитектурно недостижимо.

Второго RX backlog нет: пока один request ждёт domain processing, следующий получает `ATT_ERR_INSUFFICIENT_RESOURCES` и должен быть повторён клиентом.

## Durable transaction

`osal_snv_write()` существует только в `dpls_phy6252_storage.c`. Storage не знает, подключён ли BLE: право на один физический write ему явно передаёт runtime только после проверки, что radio offline.

Rename/setup/password/auth-lock работают как транзакция через границу радио:

```text
request
  ↓
RAM stage
  ↓
response enqueue
  ↓
GATT quiesce: новые requests запрещены
  ↓
TX drain / ATT confirmation
  ↓
runtime-controlled disconnect
  ↓
radio offline
  ↓
не более одной SNV write за OSAL turn
  ↓
все dirty records committed
  ↓
advertising
```

Ни таймер в domain, ни storage сами соединение не рвут. Journal dirty не является причиной controlled disconnect: он ждёт естественного link loss.

## Journal

В рабочей BLE-сессии journal полностью обслуживается из RAM. На flash запись имеет 12 bytes (`sequence + timestamp + type + parameter + CRC`), но RAM не копирует flash layout:

```text
200 × uint32 timestamp = 800 B
200 × uint8  type      = 200 B
200 × uint8  parameter = 200 B
                         ------
                         1200 B фактов журнала
```

`sequence` однозначно выводится из ring slot + `journal_max_sequence/journal_count`; CRC нужен только на persistence boundary. Dirty mask указывает, какие 120-byte blocks надо сохранить после disconnect.

## Bond invariant

Никаких выводов о bond по RSSI, timeout, количеству reconnect или отсутствию DPLS-auth.

`GAPBOND_ERASE_ALLBONDS` существует в first-party firmware ровно один раз: `dpls_phy6252_transport_factory_forget_bonds()`. Единственный caller — завершённый физический factory reset после удержания кнопки 5 секунд, teardown link и commit settings.

## Identity invariant

До `GAPROLE_STARTED` identity path делает только read-only операции: chip MAC и optional DID1 serial. Нет TRNG, SNV write или HCI.

После `GAPROLE_STARTED` public controller address синхронизируется с chip MAC. Ошибка identity никогда не блокирует advertising.

DID1 не владеет BLE bond identity: serial используется как product `device_id`, pairing привязан к стабильному silicon public MAC.

## Safety invariant

`dpls_safety.c` — pure policy: он не знает про OSAL, GATT, GPIO или PHY6252.

Опасный mode допустим только если одновременно истинны:

```text
connected
AND authenticated
AND fresh authenticated activity
AND required measurements valid
AND reserve not low
AND no real short
AND mode deadline alive
```

Если любой факт перестаёт быть истинным, policy требует NORMAL. Absolute mode deadline имеет детерминированный приоритет над session timeout, если они наступили одновременно.

Admission проверяется **до** аппаратного переключения. `NORMAL` всегда разрешён.

## Physical output invariant

`dpls_phy6252_outputs.c` не хранит logical mode. Он только исполняет команду GPIO.

Для любого опасного выхода порядок фиксирован:

```text
all dangerous GPIO LOW
  ↓ break-before-make
успешно захватить MOD_USR1 sleep lock
  ↓ только после success
energize ровно нужный GPIO
```

Если sleep lock не удалось захватить, функция возвращает failure, а все опасные GPIO уже LOW. `safe_normal()` сначала физически гасит outputs и только потом пытается освободить sleep lock, поэтому ошибка bookkeeping не может оставить опасный выход включённым.

## Critical fault

Domain critical fault означает «продолжать сессию небезопасно», но domain не умеет физически рвать BLE.

Он делает только:

```text
safe NORMAL
clear authenticated session/token
critical_fault = true
diagnostic_error(critical=true)
```

Runtime видит этот факт, запрещает новые GATT requests, ждёт TX drain и только затем вызывает physical disconnect.

## Factory reset

Factory reset не является BLE эвристикой. Это отдельная физическая процедура:

```text
button active at boot
  ↓ held 5 s
safe NORMAL
  ↓
stage empty settings/auth state
  ↓
quiesce + disconnect
  ↓
commit SNV offline
  ↓
erase bonds (единственное место)
  ↓
NVIC_SystemReset
```

## Build invariant

GNU Arm GCC и Arm Compiler 6.24 содержат один и тот же first-party source set. `architecture_guard.py` сравнивает оба списка и падает при расхождении.

Scatter не перечисляет каждый DPLS object вручную: `dpls*.o(+RO)` убирает третий независимый source list.

Toolchain facts pinned exactly: AC6 6.24.0, CMSIS-Toolbox 2.14.1, CMake 3.31.12, Ninja 1.13.2.

Firmverse остаётся strict и обязан увидеть реальный production boot до `LE_SetAdvEnable enabled=1`; instruction budget ограничен, а не бесконечен. Его PHY6252 HCI runtime cache инициализируется явно: erased `0xFF` mailbox bytes не могут изображать существующее BLE connection или cached ATT handle.

## Budget

Architecture guard ограничивает:

- каждый PHY adapter `.c` — максимум 600 строк;
- весь PHY adapter — максимум 2000 строк;
- весь first-party production C firmware — максимум 5000 строк.

Это не метрика красоты. Это запрет снова превратить адаптер платы в файл, который невозможно удержать в голове.

## Что больше не допускается

- второй `connected` / `link_up` / `link_active`;
- второй logical mode;
- RX backlog «на всякий случай»;
- SNV write во время active BLE;
- domain-owned physical disconnect;
- bond erase по timeout/reconnect/auth failure;
- dangerous GPIO без успешно захваченного sleep lock;
- неизвестное measurement state, трактуемое как healthy;
- toolchain/source lists, которые могут незаметно разъехаться.

## Software evidence и hardware evidence

Green CI доказывает source parity, host invariants, fault injection, mobile tests, GCC/AC6 build, strict Firmverse boot и soft-BLE E2E. Он **не доказывает**, что конкретная PB-03F физически проходит RF/pairing/power-cycle.

Release остаётся draft до отдельного реального smoke:

```text
flash
→ automatic readback PASS
→ reset
→ advertise
→ pair/auth
→ rename
→ reconnect/auth
→ 10 × link loss/reconnect
→ power cycle
→ reconnect
```

Без ручного `Forget Bluetooth` и без дополнительных действий после прошивки.

## Правило для нового кода

Новая сущность допустима только если она владеет реальным ресурсом/state либо удаляет недопустимое состояние. Нельзя добавлять второй факт, второй lifecycle, второй source list или эвристику, которая меняет durable state по косвенному сигналу.
