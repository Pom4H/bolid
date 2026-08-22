# RC9: протокол измерения энергопотребления

Цель — подтвердить требования ТЗ и одновременно проверить, что low-power runtime стабилен на реальном PHY6252.

Требования ТЗ:

- средний ток изделия ≤0,5 мА;
- пиковый ток ≤3 мА.

PB-03F-Kit содержит служебную обвязку, поэтому dev-board подходит для firmware regression/сравнений, но окончательный абсолютный ток нужно измерять на целевой аппаратной конфигурации либо отдельно учитывать ток обвязки.

## Production image

Все измерения релизного кандидата выполняются на **том же HEX**, который собирает CI:

```sh
tools/build_firmware.sh tmp/TestDPLS-1.4.2-rc9.hex
```

Toolchain и source graph не меняются между CI, Firmverse и аппаратным измерением.

Production defaults:

```text
DEBUG_INFO=0
DPLS_CONNECTED_SLEEP=1
```

UART application logging в этом image отсутствует.

## Зафиксировать образец

Для каждой серии записать:

- PCB/module revision;
- SHA `release/1.4.2-rc9`;
- SHA-256 HEX;
- напряжение питания;
- измерительный прибор и диапазон;
- телефон / ОС / версия приложения;
- расстояние и положение телефона относительно платы.

## Состояния

Перед записью среднего выдержать состояние 30 секунд; среднее считать на окне не менее 5 минут. Peak измерять прибором с достаточной полосой.

| ID | Состояние | Дополнительная проверка |
| --- | --- | --- |
| PWR-01 | cold boot → advertising | advertising не прекращается |
| PWR-02 | BLE connected, до auth | ACTIVE profile, нет reset |
| PWR-03 | authenticated NORMAL idle | стабильная session |
| PWR-04 | NORMAL + ручной refresh | возврат в idle |
| PWR-05 | dangerous mode | ACTIVE profile, ADC 250 ms, safety lease |
| PWR-06 | dangerous → NORMAL | немедленный безопасный GPIO state |
| PWR-07 | reserve, NORMAL | стабильная связь и reserve telemetry |
| PWR-08 | identify | LED + быстрый RSSI polling |
| PWR-09 | journal transfer | максимальная application activity |

## Reliability soak

Оставить production image минимум на 30–60 минут в connected NORMAL. В течение soak выполнить:

- несколько STATE refresh;
- identify;
- dangerous mode → NORMAL;
- ADC на линии и reserve;
- disconnect/reconnect;
- journal read.

PASS:

- 0 watchdog reset;
- 0 unexpected warm reset;
- 0 зависших BLE sessions;
- 0 missed forced return to `NORMAL`;
- 0 stuck dangerous GPIO;
- после reconnect состояние синхронизируется без power cycle.

## Диагностические эксперименты

Если нужно причинно проверить `DPLS_CONNECTED_SLEEP`, экспериментальный вариант собирается **тем же CMSIS project и тем же Arm Compiler 6.24.0**. Отдельный production build script или второй toolchain для этого не создаётся.

Диагностический image не заменяет release artifact и не входит в стандартный CI path. В отчёте обязательно фиксировать изменённый define.

## Логирование

Для абсолютного измерения тока не использовать `DEBUG_INFO=1`: trace создаёт дополнительную CPU/UART activity. Сначала воспроизвести ток/сбой на clean production image, затем при необходимости собирать отдельный diagnostic image тем же toolchain.

## Таблица результата

| SHA | HW | state | supply V | avg mA | peak mA | duration | resets | BLE failures | notes |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| | | advertising | | | | | | | |
| | | connected NORMAL | | | | | | | |
| | | dangerous | | | | | | | |
| | | reserve NORMAL | | | | | | | |

## Решение

- production image стабилен и укладывается в лимит → кандидат проходит power gate;
- software стабилен, но изделие выше 0,5 мА → искать dominant consumer в аппаратной части и питании;
- есть reset/BLE failure → сначала локализовать runtime/radio/ADC причину, не маскировать её watchdog timeout;
- любой экспериментальный fix после подтверждения должен снова пройти обычный single-image CI + Firmverse + hardware flow.
