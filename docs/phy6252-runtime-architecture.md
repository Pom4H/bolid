# Архитектура runtime Test-DPLS на PHY6252

## Зачем сделан рефакторинг

Аппаратные логи показали warm reset PHY6252 во время активной BLE-сессии сразу после runtime-записи SNV. До рефакторинга один `dpls_phy6252_app.c` одновременно владел BLE TX/RX, протоколом, журналом, SNV, ADC, GPIO, LED, bonding и watchdog. В кооперативном OSAL это делало блокирующую flash-операцию частью того же execution path, который должен обслуживать радио.

Цель новой архитектуры — сделать физические ресурсы явными владельцами и убрать скрытые блокирующие операции из BLE/session path.

## Слои

```text
DPLS domain
  dpls_server / dpls_protocol / dpls_safety
                |
                v
PHY6252 runtime coordinator
  dpls_phy6252_runtime.c
                |
      +---------+---------+---------+---------+
      |         |         |         |         |
 transport   storage   measurements outputs   auth
      |         |         |         |         |
 GATT/GAP    OSAL SNV     ADC      GPIO/LED  RNG/HMAC
                |
           supervisor
           watchdog policy
```

### `dpls_phy6252_runtime`

Единственный координатор доменного `dpls_server` и аппаратных адаптеров. Он знает порядок событий, но не знает деталей ADC, SNV, GATT-очередей или watchdog.

### `dpls_phy6252_transport`

Владеет BLE connection handle, RX/TX очередями, pacing одного PDU in-flight, контролем encryption timeout и bond-desync эвристикой. GATT write callback только копирует frame в очередь и будит OSAL event.

### `dpls_phy6252_storage`

Единственный владелец `osal_snv_read/write`. Настройки, auth-lock, calibration и event journal физически проходят только через этот модуль.

События журнала во время активного BLE link складываются в RAM write-behind queue. Flash journal пишется только после disconnect отдельным `DPLS_PHY6252_STORAGE_EVT`, по одному SNV-блоку за OSAL turn.

Настройки и пароль имеют синхронную durability-семантику по протоколу, поэтому их SNV transaction разрешена в сессии, но выполняется через supervisor с расширенным watchdog budget.

### `dpls_phy6252_measurements`

Единственный владелец ADC. ISR копирует raw samples и выставляет `DPLS_PHY6252_ADC_EVT`; float conversion, calibration, averaging и power-state выполняются уже в OSAL task context.

### `dpls_phy6252_outputs`

Владеет fail-safe GPIO, break-before-make переключением силовых выходов, sleep guard, LED scene и физическим factory-reset входом.

### `dpls_phy6252_auth`

Владеет PHY6252 RNG и HMAC. HMAC state остаётся static, чтобы не переполнять 1 KiB OSAL stack.

### `dpls_phy6252_supervisor`

Единственное место DPLS-кода, которому разрешено управлять watchdog. Нормально используется vendor `WDG_2S`; только на время известной блокирующей SNV/flash transaction окно расширяется до `WDG_8S`, после чего сразу возвращается к 2 секундам.

## Инварианты

1. `osal_snv_read/write` запрещены вне `dpls_phy6252_storage.c`.
2. `hal_watchdog_feed/watchdog_config` запрещены вне `dpls_phy6252_supervisor.c`.
3. `hal_adc_*` и `adc.h` запрещены вне `dpls_phy6252_measurements.c`.
4. BLE RX callback не выполняет protocol/domain work.
5. Journal flash запрещён при активном BLE link.
6. Journal flush получает отдельный OSAL event и пишет максимум один flash block за turn.
7. `dpls_server` остаётся platform-independent source of truth для auth/safety/protocol state machine.
8. Keil/AC6 остаётся production reference toolchain до отдельного hardware acceptance GNU GCC build.

Эти границы проверяет `tools/architecture_guard.py`, чтобы монолит не начал расти обратно скрытыми зависимостями.

## Ограничение write-behind журнала

RAM queue рассчитана на 32 события одной непрерывной BLE-сессии. При переполнении `events.append` возвращает ошибку доменному серверу вместо скрытой flash-записи в radio-critical path. Это намеренный fail-visible режим. Если реальные сценарии покажут, что 32 событий недостаточно, следующий шаг — отдельный append-only flash journal/FRAM, а не возврат `osal_snv_write()` в BLE path.

## Hardware acceptance после рефакторинга

Для принятия архитектуры недостаточно CI. На PHY6252 нужно подтвердить:

- длительную iOS и Android сессию без `[REST CAUSE] 1`;
- setup → disconnect → password flow;
- повторные reconnect;
- запись и экспорт журнала после disconnect;
- persistence имени/пароля после reboot;
- работу всех четырёх ADC каналов;
- fail-safe `Norma` после disconnect/reset;
- Keil/AC6 hardware run;
- отдельно — GNU GCC parity, прежде чем считать GCC production-equivalent.
