# Переход Test-DPLS на PHY62XX SDK 3.1.2

## Закреплённый SDK

Product target собирается на полном PHY62XX SDK 3.1.2 для PHY6222/PHY6252.
Upstream и точный commit закреплены в `Firmware/sdk/phy6252-sdk.env`.

Vendor SDK не изменяется в нашем репозитории:

- `tools/fetch_phy6252_sdk.sh` получает ровно закреплённый SHA;
- SDK разворачивается в `Firmware/sdk/PHY62XX_SDK_3.1.2`;
- product integration находится в `Firmware/targets/phy6252`;
- DPLS-код находится в `Firmware/src` и `Firmware/phy6252`.

Это принципиально: ADC, radio, pwrmgr, GPIO и clock должны происходить из одной
версии SDK. Смешивание драйверов 3.1.1 и библиотек 3.1.2 запрещено.

## Особенности API 3.1.2

Target учитывает несовместимости, обнаруженные при миграции:

- `GATT_MAX_NUM_CONN=MAX_NUM_LL_CONN+1`;
- `CFG_HCLK_DYNAMIC_CHANGE=0`;
- GATT read callback использует `uint16 *length`;
- GATT write callback использует `uint16 length`;
- ADC запускается через `hal_adc_start(INTERRUPT_MODE)`;
- CMSIS RTE генерируется `cbuild --update-rte`.

Несмотря на название vendor-функции `hal_ADC_compare_IRQHandler`, именно она
обслуживает `INTERRUPT_MODE` в закреплённом 3.1.2 и содержит обычный
`HAL_ADC_EVT_DATA` callback path. После one-shot callback vendor driver вызывает
`hal_adc_stop()`, отключает analog mux, возвращает GPIO во input/floating,
сбрасывает ADC context и снимает собственный `MOD_ADCC` power lock.

## Текущая архитектура adapter layer

После hardware-safety refactor низкоуровневое железо разделено по владельцам:

- `Firmware/phy6252/dpls_phy6252_hw.c` — GPIO, retention, break-before-make,
  RGB identify, P16/P17 XTAL workaround и connection-scoped `MOD_USR1` lock;
- `Firmware/phy6252/dpls_phy6252_adc.c` — весь четырёхканальный ADC state
  machine, freshness/timeout recovery и per-channel calibration;
- `Firmware/phy6252/dpls_phy6252_app.c` — предметная логика, storage и адаптация
  этих сервисов к `dpls_server`;
- `Firmware/targets/phy6252/source/dplsBLEPeripheral.c` — GAP/OSAL startup,
  SRAM retention, FS mount и интеграция с vendor stack.

`app.c` больше не должен напрямую вызывать `hal_adc_*`, `hal_pwrmgr_*` для
connection lock или инициализировать силовые GPIO. Это контролирует
`tools/check_phy6252_contract.py`.

## Четыре ADC revision 2

Встроенный ADC сканируется строго последовательно:

| Вход | GPIO | SDK mux/result |
|---|---:|---|
| +1 | P20 | `ADC_CH3P_P20` → `ADC_CH9` |
| +2 | P15 | `ADC_CH3N_P15` → `ADC_CH4` |
| +T | P24 | `ADC_CH2N_P24` → `ADC_CH2` |
| резерв | P23 | `ADC_CH1P_P23` → `ADC_CH1` |

Одновременный запуск нескольких каналов запрещён: vendor IRQ handler снимает
прерывание только при совпадении status с `all_channel`; рассинхрон нескольких
conversion исторически мог оставить IRQ неразобранным и задавить OSAL.
`Firmware/tests/test_adc_irq_model.c` фиксирует эту модель отказа.

Новый scan cycle не перезаписывает незавершённый предыдущий. Для потерянного IRQ
есть recovery timeout: зависший conversion останавливается из task context,
конкретный канал помечается неуспешным, а три остальных продолжают цикл. Если
канал перестал обновляться, validity снимается по freshness deadline — старое
число не считается живой телеметрией.

## ADC calibration

+1/+2/+T — три физически независимых делителя. У каждого свой runtime
`gain/offset`; поправка, измеренная на +1/P20, больше не копируется на +2/+T.

SNV `0x83` поддерживает совместимость со старым форматом:

- legacy v1: line calibration относится только к +1/P20, reserve — к P23;
- v2: четыре независимых `{gain, offset}` с version и CRC.

Флаг `ADC_CALIBRATED` означает, что валидна v2-калибровка всех четырёх каналов.
Reserve имеет отдельный divider и собственные sanity bounds; nominal gain 2.000
остаётся предварительным значением до измерения силовой платы.

## BLE, sleep и P16/P17

P16/P17 одновременно являются XTAL_32K pads, но Test-DPLS использует их как
KZ_2/KZ_T и работает от RC32K. Vendor startup/wake path может снова включить
32 kHz XTAL bias, поэтому `dpls_phy6252_hw.c` снимает его на старте и в wake
callback.

Исторически `dpls_phy6252_connected()` вызывал `hal_pwrmgr_lock(MOD_USR1)`, но
`MOD_USR1` не был зарегистрирован, поэтому lock был no-op. На фоне ADC clock
setup это оставляло известную ADC/radio/sleep race.

Теперь один hardware owner:

1. регистрирует `MOD_USR1` с wake callback для XTAL workaround;
2. проверяет код возврата регистрации;
3. реально lock'ит sleep на всё активное BLE connection;
4. проверяет lock result;
5. при ошибке возвращает силовые выходы в Norma и соединение не продолжает
   работу в небезопасном состоянии;
6. после disconnect unlock возвращает low-power advertising/idle.

## GPIO startup и retention

В SDK `hal_gpio_pin_init(..., GPIO_OUTPUT)` меняет DDR, но не предзагружает data
latch. Поэтому последовательность `pin_init(output) → write(0)` могла показать
сохранённую `1` как короткий active-high импульс после warm/retained reset.

Hardware owner использует обратный безопасный порядок через `hal_gpio_write(0)`:
SDK сначала пишет `swporta_dr`, затем включает output direction. Только после
этого output регистрируется для retention.

Контракт охватывает:

- ISO P31/P32/P33;
- KZ P14/P16/P17;
- RGB P07/P11/P18;
- ADC P20/P15/P24/P23;
- factory reset P34.

## SRAM/XIP и target build

SDK 3.1.2 startup по умолчанию не сохраняет все SRAM banks, которые использует
наш scatter. Target поэтому явно включает `RET_SRAM0|RET_SRAM1|RET_SRAM2`.

Retained `ER_IROM1` тесный, поэтому крупные product-модули, включая
`dpls_phy6252_hw.o`, `dpls_phy6252_adc.o` и `dpls_phy6252_app.o`, явно размещены
в `ER_ROM_XIP` scatter-файлом. Любое изменение layout должно проверяться по
свежему MAP-файлу из CI, а не по историческим числам размера.

## Persistent storage

`osal_snv` работает через FS. Pristine SDK 3.1.2 не монтировал нужный region в
нашем startup path, поэтому target до первого доступа явно монтирует
`0x1103C000`, 3 sectors.

Использование SNV:

- `0x80` settings;
- `0x81` settings marker;
- `0x82` BLE identity/MAC;
- `0x83` ADC calibration;
- `0x84` persisted auth lock;
- `0x90..0xA3` journal.

## Сборка

```sh
bash tools/build_firmware.sh tmp/test-dpls-sdk-3.1.2.hex
```

Workflow `.github/workflows/firmware-target.yml` использует:

- `ARM-software/cmsis-actions/vcpkg@v1`;
- `ARM-software/cmsis-actions/armlm@v1`;
- Arm Compiler 6;
- `cbuild`;
- `fromelf`.

Результат workflow: flashable HEX, AXF, MAP и build log. Target build должен быть
зелёным на каждом изменении firmware/hardware adapter.

## Что CI доказывает

CI подтверждает:

- host tests и lint переносимого firmware core;
- электрический pin/mode/ownership contract;
- соответствие документации source-of-truth;
- компиляцию с реальными заголовками закреплённого SDK 3.1.2;
- AC6 link/scatter и создание HEX/AXF/MAP;
- Android protocol/build tests.

CI **не** доказывает отсутствие наносекундных/микросекундных GPIO glitches,
аналоговую точность, потребление и устойчивость RF/ADC на конкретной плате.

## Что обязательно подтвердить на железе перед следующим RC

1. cold/warm reset без импульсов P31/P32/P33/P14/P16/P17;
2. ровно один active-high силовой GPIO в каждом тестовом режиме;
3. P16/P17 без паразитных уровней при connect/disconnect/wake;
4. ≥15 минут BLE connection при непрерывном сканировании всех четырёх ADC;
5. ≥2 часа ADC soak без watchdog/GATT freeze;
6. независимое изменение +1/+2/+T/reserve;
7. отдельную точность +1/+2/+T после заводской калибровки;
8. фактический reserve divider и его пороги;
9. connected и idle/advertising current.

Актуальный пошаговый список — `docs/bring-up-checklist.md`.
