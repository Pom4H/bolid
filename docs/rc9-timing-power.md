# RC9: timing, reliability and power invariants

RC9 сохраняет product protocol v2, но меняет runtime вокруг него: корректность больше не зависит от порядка независимых таймеров, а idle-состояние не должно создавать постоянную CPU/radio activity.

Главное правило:

> Время может ограничивать ожидание и задавать частоту физического измерения, но правильность состояния не зависит от того, какой независимый timer/event пришёл первым.

## Один application timer

В target существует один replaceable `SBP_DPLS_TIMER_EVT`. `dpls_phy6252_runtime_next_wakeup_ms()` выбирает ближайшее реальное событие:

- ADC sample;
- следующий LED edge;
- 5-минутный dangerous-mode deadline;
- 10-секундный authenticated-activity deadline dangerous mode;
- identify deadline;
- plaintext-link reclamation deadline;
- ATT indication confirmation deadline;
- bounded ATT retry deadline;
- physical factory-reset hold deadline.

Отдельный LED timer удалён. LED state machine возвращает точное время до следующего фронта; старый adapter cap 250 ms, который превращал длинную паузу в 4 Hz wake source, удалён.

## Adaptive ADC cadence

ADC остаётся периодическим только потому, что физику нужно наблюдать. Частота зависит от риска:

| Состояние | Интервал |
| --- | ---: |
| dangerous mode | 250 ms |
| reserve / reserve-low / auto-isolation condition | 1000 ms |
| connected authenticated NORMAL | 2000 ms |
| disconnected stable NORMAL | 5000 ms |

После RX или ADC runtime может только приблизить следующий measurement deadline, если состояние стало более опасным. Поэтому переход в dangerous state не ждёт старого idle interval.

`dpls_server_tick()` вызывается до и после semantic RX, после ADC completion и на deadline wake. Уже истёкший deadline нельзя «оживить» поздним authenticated packet.

## BLE TX без guessed completion

`DPLS_TX_NOTIFY_PACE_MS = 80 ms` удалён.

- indication остаётся `in_flight` до реального `ATT_HANDLE_VALUE_CFM`;
- Samsung-compatible CCCD `0x03` path использует notification; `GATT_Notification(...)=SUCCESS` считается completion на ATT host boundary;
- transient ATT pressure использует bounded backoff 20→40→80→160 ms;
- application delivery выше notification boundary обеспечивается request timeout/retry и idempotent server semantics.

## Adaptive BLE connection profile

Connection parameters принадлежат runtime policy, а не независимому vendor timer. `GAPROLE_PARAM_UPDATE_ENABLE` остаётся выключенным; target вызывает `GAPRole_SendUpdateParam()` только при semantic profile change.

### ACTIVE

Используется до authentication и во всех dangerous/quiescing состояниях:

- min interval: 30 ms;
- max interval: 50 ms;
- slave latency: 0.

### IDLE

Разрешён только для authenticated NORMAL:

- min interval: 120 ms;
- max interval: 150 ms;
- slave latency: 3.

Worst-case пропуск нескольких connection events остаётся в пределах порядка 600 ms, то есть профиль экономит radio activity без намеренного выхода за 1-секундный control budget.

## Mobile traffic follows risk

Телефон больше не создаёт одинаковый 1 Hz traffic во всех состояниях.

| Работа | dangerous | NORMAL |
| --- | ---: | ---: |
| `STATE_GET` cadence | 1 s | 5 s |
| telemetry stale threshold | 3 s | 15 s |
| `KEEP_ALIVE` | при необходимости, 3 s | не отправляется |
| RSSI | identify: 350 ms | обычная session: 10 s |

При mode change session loop re-arm выполняется сразу, поэтому NORMAL→dangerous не ждёт окончания старой 5-секундной паузы.

## Centralized power ownership

`dpls_phy6252_power.c` — единственный first-party владелец `hal_pwrmgr_register/lock/unlock`.

| Reason | SDK module | Удерживается |
| --- | --- | --- |
| `DPLS_POWER_LINK` | `MOD_USR0` | только в A/B reference build |
| `DPLS_POWER_OUTPUT` | `MOD_USR1` | пока dangerous GPIO может быть energized |
| `DPLS_POWER_ADC` | `MOD_USR2` | только во время ADC conversion series |

Power manager считает acquire count и суммарное время удержания каждого constraint.

### Connected sleep candidate

Production RC9 по умолчанию собирается с:

```text
DPLS_CONNECTED_SLEEP=1
DEBUG_INFO=0
```

То есть установленный BLE link сам по себе больше не держит `MOD_USR0`. BLE controller/OSAL должен будить ядро на свои события; ADC и dangerous outputs держат независимые короткие constraints.

Исторический safety barrier не удалён из кода. `DPLS_CONNECTED_SLEEP=0` возвращает link-wide `MOD_USR0` и используется как reference build для hardware A/B. Это позволяет отличить эффект deep sleep от остальных изменений без source edits.

SRAM retention `SRAM0 | SRAM1 | SRAM2` не меняется: частичная retention уже приводила к warm-reset loop.

## Production logging

Pinned PHY62XX SDK превращает `LOG(...)` в `dbg_printf(...)` при `DEBUG_INFO=1`. Для production/current measurements GCC и AC6 теперь используют `DEBUG_INFO=0`, поэтому application UART trace не искажает ток и timing.

Debug trace включается только явным diagnostic build override.

## Durable storage

Устройство ещё pre-series, поэтому RC9 удаляет migration из rc3/rc4 single-copy settings. Существует один текущий формат:

- CRC-protected dual-slot settings A/B;
- отсутствуют оба слота → `EMPTY`;
- присутствует повреждённая durable запись без валидного winner → `CORRUPT`;
- старый SNV record не может воскресить credentials.

Flash writer остаётся единственным и выполняется только offline. Journal dirty blocks сами по себе не разрывают live BLE session; critical settings/auth persistence проходит controlled quiesce.

## CI invariants

Software gates запрещают вернуть:

- global correctness tick или второй LED timer;
- direct `hal_pwrmgr_*` вне power manager;
- runtime heap в first-party production firmware;
- 80 ms notification pacing;
- fixed 1 Hz NORMAL mobile polling;
- 1 Hz background RSSI polling;
- KEEP_ALIVE в NORMAL;
- pre-series legacy settings migration;
- production `DEBUG_INFO=1`;
- потерю low-power/reference A/B build pair;
- численную cross-layer timeout ordering dependency.

CI выбирает Android/iOS/firmware по изменённым областям. PR synchronize анализирует previous PR head → new PR head, поэтому старый mobile commit не пересобирается после каждого firmware-only push. Incremental proof нельзя отменять последующим unrelated push.

## Что остаётся доказать на железе

`DPLS_CONNECTED_SLEEP=1` — намеренно hardware candidate, а не уже доказанный факт. На PHY6252/SDK 3.1.2 ранее наблюдалась sleep/radio/ADC нестабильность, поэтому release остаётся DRAFT до A/B current + reliability test.

Минимальный gate:

```text
flash + readback
→ cold boot
→ advertise
→ pair/auth
→ authenticated NORMAL idle
→ все dangerous modes + NORMAL
→ reserve/measurement fail-safe
→ reconnect ×10
→ power cycle
→ long connected idle/session
→ отсутствие watchdog/warm reset
→ current measurements
→ low-power vs link-guard A/B
```

Точный протокол: `docs/rc9-power-measurement.md`.
