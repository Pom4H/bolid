# PHY6252 / BUMBee M0 Programmer Reference

> Рабочий справочник по процессору и низкоуровневой части PHY6252 для проекта Test-DPLS.
>
> Основа: vendor SDK, закреплённый SDK 3.1.2, фактический target `Firmware/targets/phy6252`, документация Arm и публичная документация PhyPlus.

## 1. Короткий вывод

Для компилятора и программиста PHY6252 следует считать **ARMv6-M / Cortex-M0 software target**.

Это подтверждается vendor SDK:

- target называется `MCU_BUMBEE_M0`;
- `core_bumbee_m0.h` прямо описывает конфигурацию как `Cortex-M0 Processor and Core Peripherals`;
- этот wrapper подключает стандартный ARM CMSIS `core_cm0.h` и `system_ARMCM0.h`;
- `__NVIC_PRIO_BITS` задан как `2`;
- проект Test-DPLS собирается с `PHY_MCU_TYPE=MCU_BUMBEE_M0`, CMSIS CORE и ARM startup;
- linker использует M0-специфичный ROM ABI `bb_rom_sym_m0.txt`.

**Что этим не доказано:** публичных данных недостаточно, чтобы утверждать, что в кристалле находится именно лицензированный RTL Arm Cortex-M0. Совместимость по ISA/CMSIS и происхождение CPU IP — разные вещи. В публичной текущей документации PhyPlus CPU описан просто как высокопроизводительный малопотребляющий 32-bit processor.

Поэтому корректная формулировка для проекта:

> **PHY6252 использует PhyPlus target BUMBee M0 с Cortex-M0 / ARMv6-M-совместимым программным интерфейсом. Происхождение RTL CPU публично не установлено.**

Не использовать формулировки «клон Cortex-M0» или «лицензированный Cortex-M0» как установленный факт.

---

## 2. Версия SDK, на которой основан проект

Текущий production target Test-DPLS закреплён на **PHY62XX SDK 3.1.2**.

Источник задаётся в [`Firmware/sdk/phy6252-sdk.env`](../Firmware/sdk/phy6252-sdk.env):

```text
repository: xuhongv/PHY6252_6222_SDK
commit:     b7202ee56e8d316ea3451dd61266f609e6a676e8
directory:  Firmware/sdk/PHY62XX_SDK_3.1.2
```

Сам SDK не хранится целиком в git; build script загружает и проверяет закреплённый commit.

Существование 3.1.2 подтверждается непосредственно upstream `release_note.md`, где верхняя версия — `PHY62XX_SDK_3.1.2`:

- https://github.com/xuhongv/PHY6252_6222_SDK/blob/b7202ee56e8d316ea3451dd61266f609e6a676e8/release_note.md

Сборка и ловушки target: [`Firmware/README.md`](../Firmware/README.md).

При расхождении API/ROM ABI приоритет у закреплённого SDK 3.1.2.

---

## 3. CPU architecture / ISA

### 3.1 Базовая архитектура

Рабочая архитектура: **ARMv6-M**, codegen target: **Cortex-M0**, instruction state: **Thumb**.

Основные внешние документы:

- Armv6-M Architecture Reference Manual, DDI0419: https://developer.arm.com/documentation/ddi0419/latest
- Cortex-M0 Devices Generic User Guide, DUI0497: https://developer.arm.com/documentation/dui0497/latest
- Cortex-M0 instruction summary: https://developer.arm.com/documentation/dui0497/latest/the-cortex-m0-instruction-set/instruction-set-summary

Для ISA и programmer model эти документы являются базовыми, пока не найден PhyPlus-specific CPU manual, противоречащий им. На момент составления справочника публичного BUMBee M0 TRM/ISA manual не найдено.

### 3.2 Что считать доступным

Безопасный baseline — стандартный Cortex-M0 / ARMv6-M instruction subset:

- integer arithmetic / logical operations;
- shifts and rotates;
- multiply;
- loads/stores byte, halfword, word;
- stack operations `PUSH` / `POP`;
- branches, `BL`, `BX`, `BLX` в допустимых ARMv6-M формах;
- `SVC`, `BKPT`;
- `MRS` / `MSR` для доступных special registers;
- barriers `DMB` / `DSB` / `ISB`;
- low-power/event hints `WFI`, `WFE`, `SEV`.

Не рассчитывать на возможности Cortex-M3/M4 и выше:

- **нет аппаратных `SDIV` / `UDIV`** — в PHY ROM присутствуют `__aeabi_idiv*` / `__aeabi_uidiv*` helpers;
- **нет `CLZ`** как Cortex-M0 instruction;
- нет DSP extension;
- нет FPU;
- нет `BASEPRI` / `FAULTMASK` programmer model Cortex-M3+;
- нет MemManage/BusFault/UsageFault exception model Cortex-M3+;
- не предполагать exclusive-access primitives ARMv7-M (`LDREX`/`STREX`) как доступный baseline.

Если требуется hand-written asm, собирать его именно как Cortex-M0 / ARMv6-M, а не как generic Thumb-2.

### 3.3 Core registers

Стандартный ARMv6-M programmer model:

| Группа | Регистры |
|---|---|
| General purpose | `R0..R12` |
| Stack | `R13/SP`, MSP, PSP |
| Link | `R14/LR` |
| Program counter | `R15/PC` |
| Program status | `xPSR` = APSR + IPSR + EPSR |
| Special control | `PRIMASK`, `CONTROL` |

Для точного поведения special registers использовать Armv6-M ARM / Cortex-M0 Generic User Guide, а не vendor peripheral headers.

---

## 4. Exceptions, NVIC, SysTick

Vendor [`bus_dev.h`](https://github.com/xuhongv/PHY6252_6222_SDK/blob/b7202ee56e8d316ea3451dd61266f609e6a676e8/components/inc/bus_dev.h) объявляет стандартные Cortex-M0 exceptions:

| Exception | CMSIS IRQn |
|---|---:|
| NMI | -14 |
| HardFault | -13 |
| SVCall | -5 |
| PendSV | -2 |
| SysTick | -1 |

Reset является стандартным core exception, но отдельно в `IRQn_Type` этого header не перечислен.

`core_bumbee_m0.h` задаёт:

```c
#define __NVIC_PRIO_BITS 2U
```

То есть программная модель использует **4 уровня приоритета**. SDK дополнительно вводит удобные уровни:

| SDK macro | Значение |
|---|---:|
| `IRQ_PRIO_REALTIME` | 0 |
| `IRQ_PRIO_HIGH` | 1 |
| `IRQ_PRIO_HAL` | 2 |
| `IRQ_PRIO_THREAD` | 3 |
| `IRQ_PRIO_APP` | 3 |

В `mcu.h` также явно используются стандартные Cortex-M NVIC addresses:

```text
ISER 0xE000E100
ICER 0xE000E180
```

### 4.1 PHY6252 external IRQ map

По `bus_dev.h`:

| IRQ | Source |
|---:|---|
| 4 | BB / baseband |
| 5 | KSCAN |
| 6 | RTC |
| 10 | WDT |
| 11 | UART0 |
| 12 | I2C0 |
| 13 | I2C1 |
| 14 | SPI0 |
| 15 | SPI1 |
| 16 | GPIO |
| 17 | UART1 |
| 18 | SPIF |
| 19 | DMAC |
| 20 | TIMER1 |
| 21 | TIMER2 |
| 22 | TIMER3 |
| 23 | TIMER4 |
| 24 | TIMER5 |
| 25 | TIMER6 |
| 28 | AES |
| 29 | ADCC |
| 30 | QDEC |
| 31 | RNG |

Номера 0..3, 7..9 и 26..27 этим SDK как application IRQ не объявлены. Не занимать их самодельными handlers без проверки silicon/ROM integration.

---

## 5. Memory map

### 5.1 SRAM и служебные ROM области

`mcu.h` задаёт:

```text
SRAM base: 0x1FFF0000
SRAM end:  0x1FFFFFFF
```

То есть видимый диапазон — 64 KiB.

Служебные области ROM/SDK:

| Address | Назначение |
|---:|---|
| `0x1FFF0000` | ROM SRAM jump table |
| `0x1FFF0400` | ROM global config |
| `0x1FFFD000` | jump-table mirror |
| `0x1FFFD400` | global-config mirror |
| `0x1FFFE000` | ROM heap |
| `0x1FFFFC00` | ROM DWC buffer |

SDK также задаёт bank bases:

| Bank | Base |
|---|---:|
| SRAM0 | `0x1FFF0000` |
| SRAM1 | `0x1FFF4000` |
| SRAM2 | `0x1FFF8000` |

Верхний участок с `0x1FFFC000` используется target-линкером отдельно; в header он не назван `SRAM3`, поэтому этот термин здесь не вводится.

### 5.2 Фактическая раскладка Test-DPLS

[`Firmware/targets/phy6252/scatter_load.sct`](../Firmware/targets/phy6252/scatter_load.sct):

| Region | Start | Size | Назначение |
|---|---:|---:|---|
| `JUMP_TABLE` | `0x1FFF0000` | `0x400` | ROM jump table |
| `GOLBAL_CONFIG` | `0x1FFF0400` | `0x400` | ROM/global config |
| `ER_IROM1` | `0x1FFF1838` | `0x77C8` | retained SRAM code/data |
| `ER_IROM2` | `0x1FFFC000` | `0x4000` | RF PHY routines |
| `ER_ROM_XIP` | `0x11020000` | `0x20000` | XIP application/code |

Важно: `ER_IROM1` в ADC build почти заполнен; по migration notes оставалось около 1.2 KiB. Крупные static buffers или новые retained sections добавлять только после анализа MAP.

### 5.3 Flash / XIP

SDK определяет memory-mapped SPIF window:

```text
SPIF_BASE_ADDR = 0x11000000
```

Проект исполняет XIP code с `0x11020000`.

FS-backed SNV проекта размещён с `0x1103C000` и занимает три сектора в текущей схеме. Полное erase flash стирает настройки, BLE MAC, пароль/lock state и журнал; обычное обновление application area сохраняет SNV.

---

## 6. Peripheral base addresses

Основной AP peripheral space начинается с `0x40000000`.

По [`mcu_phy_bumbee.h`](https://github.com/xuhongv/PHY6252_6222_SDK/blob/b7202ee56e8d316ea3451dd61266f609e6a676e8/components/inc/mcu_phy_bumbee.h):

| Block | Base |
|---|---:|
| PCR | `0x40000000` |
| TIMER1 | `0x40001000` |
| TIMER2 | `0x40001014` |
| TIMER3 | `0x40001028` |
| TIMER4 | `0x4000103C` |
| TIMER5 | `0x40001050` |
| TIMER6 | `0x40001064` |
| TIMER SYS | `0x400010A0` |
| WDT | `0x40002000` |
| COM | `0x40003000` |
| IOMUX | `0x40003800` |
| UART0 | `0x40004000` |
| I2C0 | `0x40005000` |
| I2C1 | `0x40005800` |
| SPI0 | `0x40006000` |
| SPI1 | `0x40007000` |
| GPIO | `0x40008000` |
| UART1 | `0x40009000` |
| DMIC | `0x4000A000` |
| QDEC | `0x4000B000` |
| CACHE | `0x4000C000` |
| SPIF controller | `0x4000C800` |
| KSCAN | `0x4000D0C0` |
| PWM | `0x4000E000` |
| AON | `0x4000F000` |
| RTC | `0x4000F024` |
| PCRM | `0x4000F03C` |
| WAKEUP | `0x4000F0A0` |
| DMAC | `0x40010000` |
| ADCC | `0x40050000` |
| ADC channel data area | `0x40050400` |

Это programmer-facing register map из SDK, а не полный silicon register manual. Поля, не используемые vendor driver, считать poorly documented.

---

## 7. Clock, reset and power-control registers

### 7.1 PCR

`AP_PCR` с base `0x40000000` содержит:

| Offset | Register |
|---:|---|
| `0x00` | `SW_RESET0` |
| `0x04` | `SW_RESET1` |
| `0x08` | `SW_CLK` |
| `0x0C` | `SW_RESET2` |
| `0x10` | `SW_RESET3` |
| `0x14` | `SW_CLK1` |
| `0x18` | `APB_CLK` |
| `0x1C` | `APB_CLK_UPDATE` |
| `0x20` | `CACHE_CLOCK_GATE` |
| `0x24` | `CACHE_RST` |
| `0x28` | `CACHE_BYPASS` |

В `SW_CLK1` bit 0 называется `_CLK_M0_CPU`. В `SW_CLK` bit 0 параллельно существует `_CLK_CK802_CPU`; shared headers поддерживают более одного BUMBee CPU target и поэтому сами по себе не доказывают физическое наличие CK802 в PHY6252.

### 7.2 AON / PCRM

`AP_AON` содержит power/sleep и RTC/wakeup state (`PWROFF`, `PWRSLP`, `PMCTL*`, `RTC*`, GPIO wakeup sources, clock gate, XTAL control).

`AP_PCRM` содержит high-frequency clock/analog/ADC control:

- `CLKSEL`;
- `CLKHF_CTL0`, `CLKHF_CTL1`;
- `ANA_CTL`;
- `ADC_CTL0..ADC_CTL4`;
- calibration/efuse-related registers.

ADC driver включает analog block через `ANA_CTL bit 3` и ADC clock через `CLKHF_CTL1 bit 13`.

### 7.3 Реальные ловушки SDK 3.1.2

Из проверенного Test-DPLS target:

1. Vanilla `main.c` удерживает во сне недостаточно SRAM для нашей раскладки. Нужен `hal_pwrmgr_RAM_retention(RET_SRAM0|RET_SRAM1|RET_SRAM2)`, иначе wakeup превращается в warm reboot/reset loop.
2. При `USE_FS=1` необходимо вызвать `hal_fs_init`; иначе SNV фактически не работает.
3. Watchdog подкармливается из interrupt path: зависание application task само по себе WDT reset не вызывает.
4. `hal_pwrmgr_lock(MOD_USR1)` берётся только пока энергизован тестовый режим: sleep/wake не должен перепрограммировать GPIO под активным силовым выходом.
5. `CFG_HCLK_DYNAMIC_CHANGE=0` закреплён в target и не должен меняться без повторной hardware validation.

Подробности: [`Firmware/README.md`](../Firmware/README.md).

---

## 8. ADC

Заголовок ADC в SDK 3.1.2 документирует десять analog-capable GPIO/AIO connections:

| GPIO | AIO | Примечание |
|---|---:|---|
| P11 | 0 | ADC |
| P23 | 1 | ADC / microphone bias reference |
| P24 | 2 | ADC |
| P14 | 3 | ADC |
| P15 | 4 | ADC / microphone bias |
| P16 | 5 | 32.768 kHz XTAL input |
| P17 | 6 | 32.768 kHz XTAL output |
| P18 | 7 | PGA input + |
| P25 | 8 | ADC/differential use |
| P20 | 9 | PGA input - / ADC |

Single-ended ADC channels exposed by driver:

| Driver channel | Pin |
|---|---|
| `ADC_CH0` / enum 2 | P11 |
| `ADC_CH1` / enum 3 | P23 |
| `ADC_CH2` / enum 4 | P24 |
| `ADC_CH3` / enum 5 | P14 |
| `ADC_CH4` / enum 6 | P15 |
| `ADC_CH9` / enum 7 | P20 |

Differential pairs:

| Channel | Positive | Negative |
|---|---|---|
| `ADC_CH0DIFF` | P18 | P25 |
| `ADC_CH1DIFF` | P23 | P11 |
| `ADC_CH2DIFF` | P14 | P24 |
| `ADC_CH3DIFF` | P20 | P15 |

P16/P17 конфликтуют с 32.768 kHz crystal use. Не считать все десять AIO полноценными независимыми single-ended ADC inputs.

В Test-DPLS сканируются четыре канала, по одному за такт:

- P20 (`ADC_CH3P_P20`) — `PORT1_ADC`, клемма «+1»;
- P15 (`ADC_CH3N_P15`) — `PORT2_ADC`, клемма «+2»;
- P24 (`ADC_CH2N_P24`) — `PORT_T_ADC`, клемма «+Т»;
- P23 (`ADC_CH1P_P23`) — `VCAP_ADC`, резервный ионистор.

Соседний по паре P11 сознательно не оцифровывается: это зелёный канал RGB.

Target запускает ADC через `hal_adc_start(INTERRUPT_MODE)`. Сигнатуры — из заголовков закреплённого SDK 3.1.2, не из чужих веток.

`hal_adc_value_cal()` тянет software floating point. В текущем scatter ADC/fp objects вынесены в XIP специально, чтобы не переполнять retained SRAM.

---

## 9. GPIO / pin mux

GPIO register block (`0x40008000`) имеет стандартные для Synopsys-style GPIO поля: data, direction, interrupt enable/mask/type/polarity/status, debounce, EOI и external port input. Точные typedef/offsets находятся в `AP_GPIO_TypeDef` vendor header.

Pin mux задаётся через IOMUX block (`0x40003800`), включая analog enable, pad enable/select и pull configuration.

Production pinout Test-DPLS:

| Pin | Signal | Function |
|---|---|---|
| P31 | `ISO_1` | isolation +1 |
| P32 | `ISO_2` | isolation +2 |
| P33 | `ISO_T` | isolation +T |
| P14 | `KZ_1` | short +1 |
| P16 | `KZ_2` | short +2 |
| P17 | `KZ_T` | short +T |
| P20 | `PORT1_ADC` | voltage ADC +1 (also the legacy line channel) |
| P15 | `PORT2_ADC` | voltage ADC +2 |
| P24 | `PORT_T_ADC` | voltage ADC +T |
| P23 | `VCAP_ADC` | reserve capacitor ADC |
| P07 | `LED_RED` | status light, red channel |
| P11 | `LED_GREEN` | status light, green channel |
| P18 | `LED_BLUE` | status light, blue channel |
| P34 | `FACTORY_RESET` | physical password reset |

Источник project pinout: [`Firmware/README.md`](../Firmware/README.md) и `Firmware/phy6252/dpls_board.h`.

---

## 10. SPIF / Flash registers

SPIF controller base: `0x4000C800`.

Ключевые offsets из `AP_SPIF_TypeDef`:

| Offset | Register/function |
|---:|---|
| `0x00` | configuration |
| `0x04` | read instruction |
| `0x08` | write instruction |
| `0x0C` | device delay |
| `0x10` | read-data capture |
| `0x14` | device size |
| `0x1C` | indirect AHB trigger |
| `0x20` | DMA peripheral |
| `0x24` | remap |
| `0x40` | interrupt status |
| `0x44` | interrupt mask |
| `0x50` | lower write protection |
| `0x54` | upper write protection |
| `0x58` | write protection |
| `0x60..0x6C` | indirect read control/address/count |
| `0x70..0x7C` | indirect write control/address/count |
| `0x90` | flash command |
| `0x94` | flash command address |
| `0xA0/A4` | flash command read data |
| `0xA8/AC` | flash command write data |
| `0xB0` | polled flash status |

В production code предпочтительнее штатный SDK flash driver. Прямые register writes использовать для диагностики или только после проверки последовательностей vendor driver, особенно из-за XIP/cache/lock взаимодействий.

---

## 11. ROM ABI

PHY6252 firmware зависит не только от ISA, но и от **fixed ROM ABI**.

Target линкует:

```text
../../sdk/PHY62XX_SDK_3.1.2/misc/bb_rom_sym_m0.txt
```

Карта `bb_rom_sym_m0.txt` закреплённого SDK показывает абсолютные ROM symbols, среди которых:

- P-256 crypto primitives;
- `__aeabi_uidiv`, `__aeabi_uidivmod`, `__aeabi_idiv`, `__aeabi_idivmod`;
- memory/string helpers;
- HCI/LL implementation;
- HardFault/GPIO and другие ROM handlers/services.

**Практическое правило:** `bb_rom_sym_m0.txt` является частью ABI конкретной ветки SDK. Нельзя без проверки смешивать libraries, jump table или symbol map разных SDK.

Это уже происходило в upstream history: release notes 3.1.0 отдельно фиксируют изменения `bb_rom_sym_m0`.

CPU ISA при этом может оставаться неизменной — SDK update способен ломать firmware через ROM ABI, BLE libraries, power manager и flash driver.

---

## 12. Build target

[`Firmware/targets/phy6252/test-dpls.cproject.yml`](../Firmware/targets/phy6252/test-dpls.cproject.yml) закрепляет существенные параметры:

```text
PHY_MCU_TYPE = MCU_BUMBEE_M0
CFG_SLEEP_MODE = PWR_MODE_SLEEP
CFG_HCLK_DYNAMIC_CHANGE = 0
MTU_SIZE = 247
-fshort-enums
-funsigned-char
microlib
CMSIS CORE
ARM Device Startup
bb_rom_sym_m0.txt
```

Компилятор production build: **Arm Compiler 6.24** через CMSIS-Toolbox/cbuild.

При добавлении handwritten assembly или сторонней prebuilt library проверять, что она собрана под ARMv6-M/Cortex-M0 ABI и не содержит инструкций более старших Cortex-M.

---

## 13. Что считать authoritative

При конфликте источников использовать такой порядок:

1. **Фактически закреплённый SDK 3.1.2 + его libs / `bb_rom_sym_m0.txt`** — ABI конкретной прошивки.
2. **Project target/scatter/MAP и результаты hardware validation** — реальная интеграция PB-03F/Test-DPLS.
3. **Vendor register headers/drivers** закреплённого SDK 3.1.2 — peripheral register model.
4. **Armv6-M ARM + Cortex-M0 Generic User Guide** — ISA, core programmer model, exceptions/NVIC.
5. **PHY6252 Product Specification** — параметры SoC/package/peripherals.
6. Маркетинговые/дистрибьюторские статьи — только как hints, не как источник ABI или CPU provenance.

---

## 14. Открытые вопросы

Публично пока не найдено:

- PhyPlus `BUMBee M0 Technical Reference Manual`;
- отдельного BUMBee ISA manual;
- CPU errata/SDEN именно для PHY6252/BUMBee M0;
- документа, однозначно устанавливающего происхождение CPU RTL/IP license;
- полного официального PhyPlus register reference manual, эквивалентного обычному MCU reference manual.

Поэтому часть programmer reference фактически складывается из Arm core manuals + PhyPlus SDK headers/drivers + SoC datasheet + ROM symbol map.

Если будет получен новый SDK (например 3.1.5), перед миграцией сравнить минимум:

1. `components/arch/cm0/core_bumbee_m0.h`;
2. `components/inc/mcu.h` и `mcu_phy_bumbee.h`;
3. `components/inc/bus_dev.h` / IRQ map;
4. `misc/bb_rom_sym_m0.txt`;
5. `misc/jump_table.c`;
6. ADC/GPIO/flash/pwrmgr drivers;
7. `rf.lib`, `ble_host.lib`;
8. startup и linker assumptions;
9. release notes.

---

## 15. Sources

### Project / SDK

- [`Firmware/sdk/phy6252-sdk.env`](../Firmware/sdk/phy6252-sdk.env)
- [`Firmware/README.md`](../Firmware/README.md)
- [`Firmware/targets/phy6252/test-dpls.cproject.yml`](../Firmware/targets/phy6252/test-dpls.cproject.yml)
- [`Firmware/targets/phy6252/scatter_load.sct`](../Firmware/targets/phy6252/scatter_load.sct)
- [`Firmware/README.md`](../Firmware/README.md)
- [`core_bumbee_m0.h`](https://github.com/xuhongv/PHY6252_6222_SDK/blob/b7202ee56e8d316ea3451dd61266f609e6a676e8/components/arch/cm0/core_bumbee_m0.h)
- [`mcu.h`](https://github.com/xuhongv/PHY6252_6222_SDK/blob/b7202ee56e8d316ea3451dd61266f609e6a676e8/components/inc/mcu.h)
- [`mcu_phy_bumbee.h`](https://github.com/xuhongv/PHY6252_6222_SDK/blob/b7202ee56e8d316ea3451dd61266f609e6a676e8/components/inc/mcu_phy_bumbee.h)
- [`bus_dev.h`](https://github.com/xuhongv/PHY6252_6222_SDK/blob/b7202ee56e8d316ea3451dd61266f609e6a676e8/components/inc/bus_dev.h)
- [`adc.h`](https://github.com/xuhongv/PHY6252_6222_SDK/blob/b7202ee56e8d316ea3451dd61266f609e6a676e8/components/driver/adc/adc.h)
- [`bb_rom_sym_m0.txt`](https://github.com/xuhongv/PHY6252_6222_SDK/blob/b7202ee56e8d316ea3451dd61266f609e6a676e8/misc/bb_rom_sym_m0.txt)
- SDK 3.1.2 pinned upstream: https://github.com/xuhongv/PHY6252_6222_SDK/tree/b7202ee56e8d316ea3451dd61266f609e6a676e8

### Arm

- Armv6-M Architecture Reference Manual: https://developer.arm.com/documentation/ddi0419/latest
- Cortex-M0 Devices Generic User Guide: https://developer.arm.com/documentation/dui0497/latest
- Cortex-M0 instruction summary: https://developer.arm.com/documentation/dui0497/latest/the-cortex-m0-instruction-set/instruction-set-summary

### PhyPlus

- PHY6252 product page: https://www.phyplusinc.com/product_detail/2.html
- PHY6252 Product Specification v1.3 index: https://www.phyplusinc.com/support/1.html

---

## 16. Status of claims

| Claim | Status |
|---|---|
| PHY6252 firmware target is Cortex-M0 / ARMv6-M compatible | **Confirmed for software/toolchain use** |
| `MCU_BUMBEE_M0` is the selected PHY6252 target in Test-DPLS | **Confirmed** |
| NVIC exposes 2 implemented priority bits | **Confirmed by vendor wrapper** |
| Test-DPLS target uses SDK 3.1.2 | **Confirmed** |
| SDK 3.1.2 exists upstream | **Confirmed by release notes + pinned commit** |
| Cortex-M0-class machine code works on PB-03F silicon | **Confirmed by hardware-tested project build** |
| PHY6252 contains Arm-licensed Cortex-M0 RTL | **Not established publicly** |
| BUMBee M0 is definitely a clean-room/custom Cortex-M0 clone | **Not established publicly** |
| Public BUMBee CPU TRM exists | **Not found** |
