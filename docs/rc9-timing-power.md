# RC9: timing, reliability and power invariants

RC9 сохраняет product protocol v2, но меняет runtime вокруг него: корректность больше не зависит от порядка независимых таймеров, а idle-состояние не создаёт постоянную CPU/radio activity.

Главное правило:

> Время может ограничивать ожидание и задавать частоту физического измерения, но правильность состояния не зависит от того, какой независимый timer/event пришёл первым.

## Один application timer

В target существует один replaceable `SBP_DPLS_TIMER_EVT`. `dpls_phy6252_runtime_next_wakeup_ms()` выбирает ближайшее реальное событие:

- ADC sample;
- следующий LED edge;
- dangerous-mode deadline;
- authenticated-activity deadline;
- identify deadline;
- plaintext-link reclamation deadline;
- ATT indication confirmation/retry deadline;
- physical factory-reset hold deadline.

Отдельный LED timer отсутствует. LED state machine возвращает точное время до следующего фронта.

## Adaptive ADC cadence

| Состояние | Интервал |
| --- | ---: |
| dangerous mode | 250 ms |
| reserve / reserve-low / auto-isolation | 1000 ms |
| connected authenticated NORMAL | 2000 ms |
| disconnected stable NORMAL | 5000 ms |

После RX или ADC runtime может приблизить measurement deadline, если риск вырос. Уже истёкший deadline нельзя «оживить» поздним authenticated packet.

## BLE TX

- indication остаётся `in_flight` до реального `ATT_HANDLE_VALUE_CFM`;
- Samsung-compatible CCCD `0x03` path использует notification;
- transient ATT pressure использует bounded backoff 20→40→80→160 ms;
- application delivery выше notification boundary обеспечивается request timeout/retry и idempotent server semantics.

## Adaptive BLE connection profile

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

## Mobile traffic follows risk

| Работа | dangerous | NORMAL |
| --- | ---: | ---: |
| `STATE_GET` cadence | 1 s | 5 s |
| telemetry stale threshold | 3 s | 15 s |
| `KEEP_ALIVE` | при необходимости, 3 s | не отправляется |
| RSSI | identify: 350 ms | session: 10 s |

При mode change session loop re-arm выполняется сразу.

## Centralized power ownership

`dpls_phy6252_power.c` — единственный first-party владелец `hal_pwrmgr_register/lock/unlock`.

| Reason | SDK module | Удерживается |
| --- | --- | --- |
| `DPLS_POWER_LINK` | `MOD_USR0` | только при явно отключённом connected sleep в diagnostic experiment |
| `DPLS_POWER_OUTPUT` | `MOD_USR1` | пока dangerous GPIO может быть energized |
| `DPLS_POWER_ADC` | `MOD_USR2` | только во время ADC conversion series |

Production RC9:

```text
DPLS_CONNECTED_SLEEP=1
DEBUG_INFO=0
```

Сам факт BLE link не держит CPU awake. BLE controller/OSAL будит ядро по своим событиям; ADC и dangerous outputs удерживают отдельные короткие constraints. SRAM retention `SRAM0 | SRAM1 | SRAM2` не меняется.

## Один production build path

Production firmware имеет один source manifest (`test-dpls.cproject.yml`), один Arm Compiler 6.24.0 path и один application HEX. CI, Firmverse и реальная PB-03F работают с одним artifact.

Диагностический override параметров питания допускается только через тот же CMSIS project/toolchain и не создаёт второго production pipeline.

## Durable storage

RC9 использует один текущий CRC-protected dual-slot settings format:

- отсутствуют оба слота → `EMPTY`;
- повреждённый durable state без валидного winner → `CORRUPT`;
- flash writer единственный и выполняется только offline;
- journal dirty blocks не разрывают live BLE session;
- critical settings/auth persistence проходит controlled quiesce.

## CI invariants

Software gates запрещают вернуть:

- global correctness tick или второй LED timer;
- direct `hal_pwrmgr_*` вне power manager;
- runtime heap в first-party production firmware;
- fixed 1 Hz NORMAL mobile polling;
- background 1 Hz RSSI polling;
- KEEP_ALIVE в NORMAL;
- production `DEBUG_INFO=1`;
- второй PHY6252 production toolchain/source graph.

PR synchronize анализирует previous PR head → new PR head, поэтому unrelated platform code не пересобирается после каждого firmware-only push.

## Что остаётся доказать на железе

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
```

Точный протокол: `docs/rc9-power-measurement.md`.
