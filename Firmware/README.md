# BLE-сервер Test-DPLS для PHY6252

Каталог содержит переносимое ядро (`src/`, `include/`), PHY6252 adapter
(`phy6252/`) и product target SDK 3.1.2 (`targets/phy6252/`). Vendor SDK
загружается закреплённым скриптом и не редактируется.

## Архитектура target-слоя

После hardware-safety refactor обязанности разделены жёстко:

- `phy6252/dpls_phy6252_hw.c` — **единственный владелец цифрового железа**:
  безопасная инициализация GPIO, retention, break-before-make, RGB identify,
  P16/P17 32 kHz workaround и sleep guard активного силового режима;
- `phy6252/dpls_phy6252_adc.c` — **единственный владелец ADC**: четыре
  single-ended канала, последовательный state machine, freshness/validity и
  per-channel calibration;
- `phy6252/dpls_phy6252_app.c` — адаптер предметной логики к `dpls_server`:
  power/reserve/auto-isolation, settings, journal, transport callbacks;
- `targets/phy6252/source/dplsBLEPeripheral.c` — только интеграция vendor GAP/
  OSAL, SRAM retention, FS mount и запуск DPLS adapter.

Низкоуровневые GPIO/ADC/pwrmgr операции не должны возвращаться в `app.c` или
BLE target. Это проверяет `tools/check_phy6252_contract.py` в CI.

## Электрический контракт revision 2

| Роль | GPIO | ADC/примечание |
|---|---:|---|
| ISO_1 / ISO_2 / ISO_T | P31 / P32 / P33 | active-high |
| KZ_1 / KZ_2 / KZ_T | P14 / P16 / P17 | active-high |
| +1 | P20 | ADC_CH9 |
| +2 | P15 | ADC_CH4 |
| +T | P24 | ADC_CH2 |
| резерв | P23 | ADC_CH1 |
| RGB R/G/B | P07 / P11 / P18 | identify = green |
| factory reset | P34 | physical only |

Все управляющие линии имеют fail-safe 0 = «Норма». `DPLS_PIN_LINE_ADC` — только
legacy-алиас +1/P20, а не отдельный физический вход.

### Почему GPIO и sleep вынесены отдельно

`hal_gpio_pin_init(..., GPIO_OUTPUT)` в PHY62XX SDK меняет DDR, но не
предзагружает data latch. После warm/retained reset это могло кратко показать
старую `1` на active-high выходе. Hardware adapter использует
`hal_gpio_write(pin, 0)`, который сначала записывает latch и лишь затем включает
output direction, после чего регистрирует retention.

P16/P17 одновременно являются XTAL_32K pads PHY6252. На Test-DPLS кварца нет,
используется RC32K, поэтому hardware adapter снимает vendor XTAL bias на старте
и в wake callback.

У SDK 3.1.2 `INTERRUPT_MODE` ошибочно выбирал compare/debug ISR вместо обычного
one-shot ADC handler. Product build детерминированно патчит этот выбор на
`hal_ADC_IRQHandler`; тот завершает conversion через штатный cleanup и снимает
собственный `MOD_ADCC` power lock.

`MOD_USR1` зарегистрирован hardware owner-ом и блокирует sleep на всё время
интерактивного BLE-сеанса. На закреплённом PHY62xx SDK sleep/wake может потерять
ATT-ответ на длинную запись и заблокировать очередь Android GATT. Ненормальный
силовой режим (`OPEN_*` / `SHORT_*`) также удерживает guard; после disconnect и
возврата в `NORMAL` он снимается.

Во время активного BLE-сеанса все четыре канала ADC продолжают обновляться в
реальном времени. Контроллер выдаёт событие окончания каждого connection event;
прошивка запускает в следующем тихом окне ровно одно преобразование. Полный цикл
`+1/+2/+T/reserve` завершается за четыре BLE-интервала, не пересекая радиообмен.

## ADC revision 2

Один встроенный ADC сканирует строго по одному каналу:

`P20/+1 → P15/+2 → P24/+T → P23/reserve`.

Новый цикл начинается только когда предыдущий полностью завершён. Ошибка одного
conversion не блокирует остальные каналы; канал повторяется в следующем цикле.
Старое значение перестаёт считаться valid после freshness deadline, поэтому
зависший ADC не выглядит как «живое» неизменное напряжение.

Три DPLS-делителя имеют отдельные runtime `gain/offset`. Legacy SNV `0x83`
содержал только одну line calibration: при миграции она относится только к
P20/+1 и **не копируется** на +2/+T. Новый v2-формат поддерживает четыре
отдельные calibration record с version+CRC. `ADC_CALIBRATED` выставляется только
когда валидны все четыре.

Reserve — отдельный divider. Его nominal gain 2.000 пока остаётся
предварительным значением до измерения силовой платы; для него используются
собственные sanity bounds, а не 20x–45x bounds DPLS line divider.

## Сборка и тесты

```sh
# portable core
cmake -S Firmware -B Firmware/build
cmake --build Firmware/build
ctest --test-dir Firmware/build --output-on-failure

# electrical/source contract
python3 tools/check_phy6252_contract.py

# target SDK 3.1.2 / Arm Compiler 6
tools/build_firmware.sh tmp/test-dpls-sdk-3.1.2.hex
```

CI также собирает Android release и генерирует интерактивную hardware model:

```sh
python3 tools/generate_behavior_sim.py --output /tmp/test-dpls-behavior-sim.html
```

Generated HTML не хранится в git: модель интроспектирует текущие `board.h`,
`dpls_server.h`, hardware owner, ADC owner и app thresholds.

## Vendor SDK 3.1.2: обязательные интеграционные поправки

Product target сохраняет только реально необходимые особенности vendor runtime:

1. retained только `SRAM0`; linker MAP gate (`tools/check_phy6252_map.py`)
   блокирует сборку, если live `ER_IROM1` снова пересечёт границу SRAM0. `SRAM1`
   и `SRAM2` намеренно не удерживаются во сне;
2. mount fs @ `0x1103C000`, 3 sectors до первого `osal_snv` access;
3. vendor ADC source патчится `tools/patch_phy6252_sdk.py` по точному тексту
   закреплённого SDK commit; upstream drift делает build красным вместо fuzzy
   patch;
4. product startup не содержит vendor DTM/demo-вход по P20: P20 принадлежит
   измерению +1 и не может использоваться как boot-mode selector.

Менять retention или ADC cleanup без нового MAP check и hardware soak нельзя.

## Persistent storage

- settings `0x80`;
- settings marker `0x81`;
- BLE MAC `0x82`;
- ADC calibration `0x83`;
- auth-lock `0x84`;
- journal `0x90..0xA3`.

Обычная прошивка сохраняет SNV; flash с `--erase` очищает его полностью.

## Что проверять на новом стенде

Полный порядок — [`docs/bring-up-checklist.md`](../docs/bring-up-checklist.md).
Критические acceptance checks перед RC:

- reset/warm-reset без кратких импульсов на P31/P32/P33/P14/P16/P17;
- каждый mode активирует ровно один из шести выходов;
- P16/P17 стабильны при connect/disconnect и длительной BLE-сессии;
- ≥15 минут BLE + постоянный четырёхканальный ADC без GATT loss;
- изменение каждого потенциометра влияет только на свой +1/+2/+T/reserve;
- ошибка/обрыв одного ADC не останавливает остальные;
- измерить advertising, connected-idle и active-mode current отдельно;
- target AC6 build и все CI checks зелёные;
- после заводской калибровки отдельно подтверждена точность каждого DPLS-входа.
