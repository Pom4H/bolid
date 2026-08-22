# Runtime-архитектура RC9

RC9 уменьшает количество мест, где может находиться истина. Production-код должен читаться сверху вниз: факт принадлежит одному владельцу, физический ресурс — одному адаптеру, переход состояния — одному reducer/policy.

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
  dpls_durable_settings.c    — dual-slot settings record

firmware/phy6252
  dpls_ble_identity.c        — chip identity
  dpls_gatt_service.c        — RX/TX characteristics + CCCD
  dpls_phy6252_auth.c        — TRNG/HMAC
  dpls_phy6252_transport.c   — единственный владелец BLE link/RX/TX
  dpls_phy6252_storage.c     — единственный app-owned SNV writer
  dpls_phy6252_measurements.c— ADC / power facts
  dpls_phy6252_outputs.c     — GPIO / LED actuator
  dpls_phy6252_power.c       — единственный pwrmgr owner
  dpls_phy6252_supervisor.c  — watchdog boundary
  dpls_phy6252_runtime.c     — orchestration физических owners

firmware/targets/phy6252
  test-dpls.cproject.yml     — единственный production source manifest
  scatter_load.sct           — memory map
```

## Кто владеет фактами

| Факт / ресурс | Единственный владелец |
| --- | --- |
| Физическое BLE-соединение | `dpls_phy6252_transport.connection_handle` |
| Lifecycle приложения | `DeviceSession` + `ConnectionMachine.reduce()` |
| Опасный logical mode | `dpls_safety_t.mode` |
| Физические силовые GPIO | `dpls_phy6252_outputs.c` |
| Measurement validity / voltage / power | `dpls_phy6252_measurements.c` |
| App-owned SNV writes | `dpls_phy6252_storage.c` |
| Power-manager constraints | `dpls_phy6252_power.c` |
| Решение о physical disconnect | `dpls_phy6252_runtime.c` |
| Bond erase | physical factory-reset path |

## BLE transaction

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

До приёма request transport резервирует место под будущий response. Состояние «команда выполнена, но ACK некуда положить» архитектурно недостижимо.

## Durable transaction

`osal_snv_write()` существует только в `dpls_phy6252_storage.c`. Право на physical write runtime передаёт только после проверки, что radio offline.

```text
request
  ↓
RAM stage
  ↓
response enqueue
  ↓
GATT quiesce
  ↓
TX drain / confirmation
  ↓
runtime-controlled disconnect
  ↓
radio offline
  ↓
SNV write
  ↓
advertising
```

## Bond invariant

Никаких выводов о bond по RSSI, timeout, reconnect count или отсутствию DPLS-auth. Удаление keys разрешено только завершённым physical factory reset flow.

## Safety invariant

`dpls_safety.c` — pure policy. Dangerous mode допустим только если одновременно истинны:

```text
connected
AND authenticated
AND fresh authenticated activity
AND required measurements valid
AND reserve not low
AND no real short
AND mode deadline alive
```

Если любой факт перестаёт быть истинным, policy требует `NORMAL`.

## Physical output invariant

`dpls_phy6252_outputs.c` не хранит logical mode. Для dangerous output порядок фиксирован:

```text
all dangerous GPIO LOW
  ↓ break-before-make
успешно захватить output power constraint
  ↓
energize ровно нужный GPIO
```

Ошибка power bookkeeping не может оставить dangerous output включённым.

## Один production build

Production PHY6252 имеет один source manifest, один pinned Arm Compiler 6.24.0 и один application HEX. Scatter file не дублирует source list.

Toolchain facts pinned exactly:

- Arm Compiler 6.24.0;
- CMSIS-Toolbox 2.14.1;
- CMake 3.31.12;
- Ninja 1.13.2;
- PHY62XX SDK 3.1.2.

```text
CMSIS project
  ↓
production HEX
  ├─ CI artifact
  ├─ Firmverse
  └─ PB-03F
```

Второго production target graph нет. `tools/architecture_guard.py`, `tools/check_repo_layout.sh` и `tools/test_ci_contract.py` защищают этот invariant.

## Firmverse

Firmverse strict обязан увидеть реальный production boot до `LE_SetAdvEnable enabled=1`. Он получает уже собранный production artifact и не пересобирает firmware.

## Cognitive budget

Architecture guard ограничивает:

- каждый PHY adapter `.c` — максимум 600 строк;
- весь PHY adapter — максимум 2300 строк;
- весь first-party production C firmware — максимум 5000 строк.

## Software evidence и hardware evidence

Green CI доказывает host invariants, fault injection, mobile tests, production build, strict Firmverse boot и soft-BLE E2E. Он не доказывает, что конкретная PB-03F физически проходит RF/pairing/power-cycle/current acceptance.

Release остаётся draft до реального smoke:

```text
build
→ flash/readback
→ reset
→ advertise
→ pair/auth
→ rename
→ reconnect/auth
→ repeated link loss/reconnect
→ power cycle
→ current measurement
```

## Правило для нового кода

Новая сущность допустима только если она владеет реальным resource/state либо удаляет недопустимое состояние. Нельзя добавлять второй факт, lifecycle, production source list или косвенную эвристику, меняющую durable state.
