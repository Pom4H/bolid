# Runtime-архитектура RC8

RC8 специально уменьшает количество мест, где может находиться истина. Production-код должен читаться сверху вниз за один проход.

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
  dpls_phy6252_transport.c   — BLE link + RX/TX queues
  dpls_phy6252_storage.c     — единственный app-owned SNV writer
  dpls_phy6252_measurements.c— ADC / power facts
  dpls_phy6252_outputs.c     — GPIO / LED / safe output state
  dpls_phy6252_supervisor.c  — watchdog boundary
  dpls_phy6252_runtime.c     — OSAL orchestration, без business logic

firmware/targets/phy6252/source/dplsBLEPeripheral.c
  — тонкий vendor GAP/OSAL shell
```

Старого `dpls_phy6252_app.c` нет. Второго storage facade нет. Второго BLE lifecycle нет.

## Главный поток

```text
GATT write callback
  ↓ copy to RX queue
DPLS_PHY6252_RX_EVT
  ↓
dpls_phy6252_runtime_process_rx
  ↓
dpls_server_receive
  ↓
RAM state / response enqueue
  ↓
DPLS_PHY6252_TX_EVT
  ↓
notify/indication
  ↓
TX drain
  ↓ (только если settings/auth dirty)
controlled disconnect
  ↓
DPLS_PHY6252_STORAGE_EVT
  ↓
одна SNV write за OSAL turn
  ↓
advertising
```

## Flash invariant

`osal_snv_write()` разрешён только в `dpls_phy6252_storage.c`.

Во время active BLE link он физически отвергается. Поэтому rename/setup/password работают так:

```text
command → RAM stage → SETTINGS/AUTH response → TX drain → disconnect → commit → advertise
```

Journal проще: все 200 записей (2400 bytes) находятся в RAM во время работы. Append/read никогда не обращаются к flash. Dirty 120-byte blocks сохраняются после естественного disconnect. Journal сам рабочую сессию не рвёт.

## Bond invariant

Никаких выводов о bond по RSSI, timeout, количеству reconnect или отсутствию DPLS-auth.

`GAPBOND_ERASE_ALLBONDS` существует в first-party firmware ровно один раз: `dpls_phy6252_transport_factory_forget_bonds()`. Единственный caller — завершённый физический factory reset после удержания кнопки 5 секунд, teardown link и commit settings.

## Identity invariant

До `GAPROLE_STARTED` identity path делает только read-only операции: chip MAC и optional DID1 serial. Нет TRNG, SNV write или HCI.

После `GAPROLE_STARTED` public controller address синхронизируется с chip MAC. Ошибка identity никогда не блокирует advertising.

DID1 больше не владеет BLE bond identity: его serial используется как product `device_id`, а pairing привязан к стабильному silicon public MAC.

## Safety

`dpls_safety.c` не знает про OSAL/GATT/GPIO и единолично решает, когда опасный режим обязан вернуться в NORMAL.

`dpls_phy6252_outputs.c` единолично применяет физические выходы и всегда использует break-before-make.

## Build invariant

GNU Arm GCC и Arm Compiler 6.24 содержат один и тот же first-party source set. `architecture_guard.py` сравнивает оба списка и падает при расхождении.

Scatter не перечисляет каждый DPLS object вручную: `dpls*.o(+RO)` убирает третий независимый source list.

## Budget

Architecture guard ограничивает:

- каждый PHY adapter `.c` — максимум 600 строк;
- весь PHY adapter — максимум 2000 строк;
- весь first-party production C firmware — максимум 5000 строк.

Это не метрика красоты. Это запрет снова превратить адаптер платы в файл, который невозможно удержать в голове.

## Правило для нового кода

Новая сущность допустима только если она владеет реальным ресурсом/state либо удаляет недопустимое состояние. Нельзя добавлять второй `connected`, второй `pending`, второй lifecycle, второй source list или эвристику, которая меняет durable state по косвенному сигналу.
