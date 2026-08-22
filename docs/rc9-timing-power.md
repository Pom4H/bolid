# RC9: timing, reliability and power invariants

RC9 не меняет продуктовый протокол и не пытается оптимизировать PHY6252 ценой уже доказанной стабильности. Цель релиза уже: убрать зависимость корректности от порядка таймаутов и одновременно уменьшить лишние пробуждения CPU.

Главное правило:

> Время может ограничивать ожидание и задавать частоту физического измерения, но правильность состояния не зависит от того, какой независимый таймер сработал первым.

## Один runtime timer вместо correctness polling

В RC8 target shell будил runtime фиксированно раз в 1 секунду при BLE link и раз в 5 секунд без link. Этим же тиком проверялись safety deadlines, transport deadlines, factory reset и запуск ADC.

В RC9 target содержит один replaceable `SBP_DPLS_TIMER_EVT`. Runtime вычисляет ближайший абсолютный monotonic deadline через `dpls_phy6252_runtime_next_wakeup_ms()`. После любого события, которое меняет набор deadlines, target переустанавливает этот one-shot timer.

В ближайший wakeup входят:

- следующий ADC sample;
- 5-минутный deadline опасного режима;
- 10-секундный deadline authenticated activity в опасном режиме;
- identify deadline;
- plaintext-link resource deadline;
- ATT indication confirmation deadline;
- bounded ATT retry deadline;
- физический factory-reset hold deadline.

Периодическим остаётся только наблюдение физики:

- `1000 ms` во время BLE session;
- `5000 ms` вне BLE session.

Это sampling cadence, а не application correctness tick.

## Event-driven safety

`dpls_server_tick()` теперь вызывается не только на deadline wakeup, но и после semantic RX и после каждого ADC completion.

Поэтому изменение `reserve_low`, measurement validity или real-short-derived state влияет на safety сразу после получения свежего физического измерения. Дополнительной задержки до следующего 1 Hz application tick больше нет.

Коллизии событий разрешаются pure policy. Например, если 5-минутный mode deadline и session deadline наступили в один момент, результат детерминирован. Если одновременно появился физический fail-safe факт, физическая причина имеет приоритет. Это закреплено `test_deadline_collision_precedence()`.

## BLE TX без guessed completion

RC8 использовал `80 ms` как предполагаемое завершение notification. RC9 удаляет `DPLS_TX_NOTIFY_PACE_MS`.

Два допустимых пути:

1. ATT indication: frame остаётся `in_flight` до настоящего `ATT_HANDLE_VALUE_CFM`; если confirmation не приходит за 2 секунды, link освобождается через disconnect.
2. Samsung-compatible notification: `GATT_Notification(...)=SUCCESS` означает завершение на ATT host boundary. Дополнительного искусственного ожидания нет.

Samsung workaround сохранён намеренно: SM-A135F в уже проверенном пути требует CCCD `0x03`, поэтому indication-only был бы недопустимой регрессией ради архитектурной чистоты.

Transient `blePending` / allocation pressure не создают spin loop. Transport использует bounded exponential backoff 20→40→80→160 ms и сообщает ближайший retry runtime scheduler.

## Независимые timeout domains

Mobile connect timeout и firmware plaintext-link timeout остаются локальными механизмами liveness/resource reclamation. RC9 больше не требует численного порядка между ними.

Удалён старый CI-инвариант `mobile timeout < firmware timeout минимум на 5 секунд`. Новый gate запрещает возвращать такую зависимость.

Поздний callback после локального timeout должен быть harmless благодаря lifecycle generation/state checks, а firmware не делает destructive conclusions о bond по timeout.

## Monotonic epoch clock на mobile

`DplsPlatformServices.nowMillis()` остаётся epoch-compatible, потому что это значение используется для `TIME_SYNC`, но его ход теперь монотонный:

- Android: `System.currentTimeMillis()` берётся один раз как epoch anchor, дальше используется `SystemClock.elapsedRealtime()`;
- iOS: wall clock берётся один раз, дальше используется `NSProcessInfo.systemUptime`;
- web lab: `Date.now()` anchor + `performance.now()` delta.

Поэтому telemetry stale, identify phase и другие elapsed-time решения не прыгают при NTP или ручном изменении часов.

## Power ownership

RC9 уменьшает software wakeups, но не снимает уже доказанные hardware safety barriers.

PHY6252 использует три независимых sleep owner:

| Owner | Ресурс | Когда удерживается |
| --- | --- | --- |
| `MOD_USR0` | BLE link stability | вся физическая BLE session |
| `MOD_USR1` | dangerous outputs | только пока опасный GPIO может быть energized |
| `MOD_USR2` | ADC series | только во время короткой серии ADC conversions |

`MOD_USR0` пока нельзя удалять ради снижения потребления. На реальной PB-03F с SDK 3.1.2 уже наблюдался ADC/radio sleep race, который замораживал OSAL loop / приводил к watchdog resets. Снятие этого lock требует отдельного hardware proof, а не только green CI.

Также сохраняется retention `SRAM0 | SRAM1 | SRAM2`: текущий scatter размещает живые секции во всех трёх банках, и уже был реальный warm-reset loop при неполной retention.

Следовательно RC9 оптимизирует то, что можно оптимизировать безопасно: количество лишних timer wakeups, spin/retry behavior и software race-space. Минимизация connected-session current за счёт deep sleep — отдельная hardware-задача после стабилизации release.

## CI invariants

`tools/test_ble_timeout_contract.py` в RC9 проверяет:

- отсутствие глобального `DPLS_TICK_MS` / `DPLS_TICK_IDLE_MS` correctness poll;
- наличие одного replaceable runtime timer;
- отсутствие `DPLS_TX_NOTIFY_PACE_MS`;
- наличие explicit transport deadlines и bounded retry backoff;
- safety reconciliation в ADC event path;
- monotonic clock progression Android/iOS/web;
- отсутствие дополнительного Android pairing timeout;
- сохранение трёх PHY6252 sleep owners и симметричных lock/unlock barriers;
- отсутствие численной зависимости mobile timeout от firmware timeout.

Host safety tests дополнительно проверяют collision precedence и fail-safe state space.

## Hardware release gate

Green software CI всё ещё недостаточен для утверждения connected-session power и PHY6252 sleep stability. Перед признанием RC9 hardware-ready нужен физический smoke минимум:

```text
flash + readback
→ cold boot
→ advertise
→ pair/auth
→ все опасные modes + NORMAL
→ reserve/measurement fail-safe
→ rename/password persistence
→ 10 × disconnect/reconnect
→ power cycle
→ reconnect/auth
→ длительная session без watchdog/warm reset
→ измерение тока idle / connected / reserve-source
```

Любая будущая попытка снять `MOD_USR0` или уменьшить SRAM retention должна проходить этот hardware gate отдельно.