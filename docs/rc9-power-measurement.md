# RC9: протокол измерения энергопотребления и connected-sleep A/B

Цель измерения — отдельно ответить на два вопроса:

1. выполняет ли целевое изделие требование ТЗ по току: средний ток не более 0,5 мА, пиковый до 3 мА;
2. сколько именно даёт RC9 connected sleep и не возвращает ли он PHY6252 radio/ADC instability.

Важно: полная PB-03F-Kit содержит служебную обвязку и сама по себе не подходит для окончательного доказательства лимита 0,5 мА. На dev-board можно достоверно сравнивать firmware A/B и искать wakeup/current regressions. Абсолютный приёмочный ток нужно измерять на целевой аппаратной конфигурации либо отдельно учитывать ток обвязки.

## 1. Зафиксировать образец

Для каждой серии записать:

- PCB / module revision;
- SHA `release/1.4.2-rc9`;
- имя HEX;
- SHA-256 HEX;
- toolchain;
- `DEBUG_INFO`;
- `DPLS_CONNECTED_SLEEP`;
- напряжение питания;
- измерительный прибор и диапазон;
- телефон / ОС / версия приложения;
- расстояние и положение телефона относительно платы.

Не менять положение телефона между A/B: RF retransmissions способны изменить средний ток сильнее части software optimization.

## 2. Production absolute-current image

Основной release/current image собирается AC6 с:

```text
DEBUG_INFO=0
DPLS_CONNECTED_SLEEP=1
```

UART application logging в этом image отсутствует.

Измерить минимум следующие состояния. Перед записью среднего выдержать состояние 30 секунд; среднее считать на окне не менее 5 минут, отдельно сохранить максимальный наблюдаемый пик.

| ID | Состояние | Что проверить вместе с током |
| --- | --- | --- |
| PWR-01 | cold boot → advertising, BLE disconnected | стабильный boot, advertising не прекращается |
| PWR-02 | BLE connected, до auth | ACTIVE connection profile, нет reset |
| PWR-03 | authenticated NORMAL idle | IDLE connection profile, приложение остаётся responsive |
| PWR-04 | authenticated NORMAL + ручной refresh/UI | реакция на запросы, возврат в idle |
| PWR-05 | dangerous mode | ACTIVE profile, ADC 250 ms, safety lease работает |
| PWR-06 | возврат dangerous → NORMAL | немедленный GPIO NORMAL, затем переход BLE в IDLE |
| PWR-07 | питание от reserve, NORMAL | reserve indication/ADC, стабильная связь |
| PWR-08 | identify | LED + быстрый RSSI polling, 15-секундный сценарий |
| PWR-09 | journal transfer | максимальная реальная BLE application activity |

Для требования 0,5 мА в первую очередь важен установившийся дежурный режим целевого изделия. Пики до 3 мА нужно смотреть измерителем/осциллографом с достаточной полосой; обычный мультиметр может их усреднить и не доказать ограничение.

## 3. Connected-sleep A/B без source edits

Собрать пару из одного SHA:

```bash
bash tools/build_power_ab.sh
```

Получаются:

```text
tmp/power-ab/TestDPLS-1.4.2-rc9-low-power.hex
  DEBUG_INFO=0
  DPLS_CONNECTED_SLEEP=1

tmp/power-ab/TestDPLS-1.4.2-rc9-link-guard.hex
  DEBUG_INFO=0
  DPLS_CONNECTED_SLEEP=0
```

Это GNU Arm diagnostic pair. Она нужна для причинного сравнения одного фактора — whole-link `MOD_USR0`. Абсолютный release result подтверждать AC6 production image.

### A/B последовательность

Для каждого image выполнить в одинаковом порядке:

1. flash + readback;
2. cold boot;
3. 2 минуты advertising;
4. pair/auth;
5. 5 минут authenticated NORMAL idle;
6. 10 переходов `NORMAL → dangerous → NORMAL`;
7. 10 disconnect/reconnect;
8. 10 минут connected NORMAL idle;
9. одна выгрузка журнала;
10. power cycle и повторный auth.

Записать средний/peak current отдельно для advertising, connected NORMAL и dangerous mode.

Если `low-power` нестабилен, а `link-guard` на той же плате/питании/телефоне воспроизводимо стабилен, это сильный указатель на PHY6252 sleep/radio/ADC path. Не маскировать такой результат увеличением watchdog timeout.

## 4. Reliability soak low-power candidate

После короткого A/B оставить `DPLS_CONNECTED_SLEEP=1` минимум на 30–60 минут в connected NORMAL. В течение soak выполнить:

- периодический STATE refresh;
- несколько identify;
- несколько dangerous mode + NORMAL;
- ADC на линии и reserve;
- disconnect/reconnect;
- journal read.

PASS по software reliability для этого опыта:

- 0 watchdog reset;
- 0 unexpected warm reset;
- 0 зависших BLE sessions;
- 0 missed forced return to NORMAL;
- 0 stuck dangerous GPIO;
- после reconnect состояние синхронизируется без power cycle.

## 5. Что логировать, не включая UART production trace

Для измерения тока нельзя собирать production с `DEBUG_INFO=1`: `LOG()` создаёт дополнительную UART/CPU activity.

Если нужна диагностика причины, сначала воспроизвести ток/сбой с clean image, затем отдельно собрать debug image. Не сравнивать абсолютный ток clean и debug firmware как эквивалентные варианты.

`dpls_phy6252_power` уже считает в RAM:

- число acquire каждого power reason;
- суммарное время удержания LINK/OUTPUT/ADC constraint;
- текущую mask удержаний.

Эти counters предназначены для последующего diagnostic exposure; они не создают periodic wakeup сами по себе.

## 6. Таблица результата

Заполнять одну строку на устойчивое состояние:

| SHA | HW | image | state | supply V | avg mA | peak mA | duration | resets | BLE failures | notes |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| | | | advertising | | | | | | | |
| | | | connected NORMAL | | | | | | | |
| | | | dangerous | | | | | | | |
| | | | reserve NORMAL | | | | | | | |

Для A/B добавить вычисление:

```text
connected_sleep_saving_mA = avg(link-guard) - avg(low-power)
connected_sleep_saving_%  = saving / avg(link-guard) × 100
```

## 7. Решение после измерений

- low-power стабилен и ток ниже → оставить `DPLS_CONNECTED_SLEEP=1`;
- разницы тока почти нет → искать следующий dominant consumer, не усложнять sleep path без выгоды;
- low-power нестабилен, link-guard стабилен → вернуть guard как release default и локализовать vendor sleep race отдельно;
- оба нестабильны → проблема не доказывается whole-link sleep guard, искать radio/ADC/state issue дальше;
- firmware delta хороший, но изделие выше 0,5 мА → измерять quiescent current силовой/питающей части: software уже не единственный источник бюджета.
