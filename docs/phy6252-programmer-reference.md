# PHY6252 / BUMBee M0: programmer reference

Рабочий low-level reference для Test-DPLS. Здесь зафиксированы только те свойства PHY6252, на которые реально опираются target, linker, firmware и hardware bring-up.

## 1. CPU: что установлено

Для компилятора PHY6252 в этом проекте является **ARMv6-M / Cortex-M0 software target**.

Основание в закреплённом vendor SDK 3.1.2:

- target `MCU_BUMBEE_M0`;
- `core_bumbee_m0.h` описывает Cortex-M0 processor/core peripherals;
- CMSIS wrapper использует `core_cm0.h` и `system_ARMCM0.h`;
- `__NVIC_PRIO_BITS = 2`;
- Test-DPLS собирается как M0 и использует ROM symbol map `bb_rom_sym_m0.txt`.

Это подтверждает ISA/CMSIS/ABI-совместимость программного target, но не происхождение RTL. Публичных материалов недостаточно, чтобы называть BUMBee либо «клоном Cortex-M0», либо подтверждённым лицензированным Cortex-M0 IP.

Корректная формулировка:

> PHY6252 использует PhyPlus BUMBee M0 target с Cortex-M0 / ARMv6-M-совместимым программным интерфейсом; происхождение CPU RTL публично не установлено.

## 2. Закреплённый SDK

Production target использует **PHY62XX SDK 3.1.2**.

Source of truth: [`firmware/sdk/phy6252-sdk.env`](../firmware/sdk/phy6252-sdk.env).

```text
repository: xuhongv/PHY6252_6222_SDK
commit:     b7202ee56e8d316ea3451dd61266f609e6a676e8
directory:  firmware/sdk/PHY62XX_SDK_3.1.2
```

Полный SDK не vendored. `tools/fetch_phy6252_sdk.sh` загружает и проверяет точный commit.

Обновление SDK — отдельная миграция: даже при той же ISA могут измениться ROM ABI, BLE/RF libraries, jump table, flash/ADC/power behavior и linker assumptions.

## 3. ISA baseline

Codegen: ARMv6-M, Thumb, Cortex-M0.

Можно рассчитывать на стандартный M0 subset:

- integer arithmetic/logical operations;
- shifts/rotates и multiply;
- byte/halfword/word loads/stores;
- `PUSH` / `POP`;
- branches, `BL`, `BX` и допустимые ARMv6-M формы `BLX`;
- `SVC`, `BKPT`;
- `MRS` / `MSR` для доступных special registers;
- `DMB`, `DSB`, `ISB`;
- `WFI`, `WFE`, `SEV`.

Не считать baseline возможностями M3/M4+:

- аппаратные `SDIV` / `UDIV`;
- `CLZ` как гарантированную M0 instruction;
- DSP extension;
- FPU;
- `BASEPRI` / `FAULTMASK`;
- MemManage/BusFault/UsageFault model;
- ARMv7-M exclusive-access assumptions (`LDREX` / `STREX`).

ROM экспортирует `__aeabi_idiv*` / `__aeabi_uidiv*`, поэтому обычное C integer division остаётся доступным через helper ABI.

Hand-written assembly собирать под Cortex-M0 / ARMv6-M, не под generic Thumb-2.

## 4. Core registers, exceptions и IRQ

Programmer model:

| Группа | Регистры |
|---|---|
| general | `R0..R12` |
| stack | `R13/SP`, MSP, PSP |
| link | `R14/LR` |
| PC | `R15/PC` |
| status | `xPSR` (APSR/IPSR/EPSR) |
| control | `PRIMASK`, `CONTROL` |

SDK использует стандартные core exceptions: NMI, HardFault, SVCall, PendSV и SysTick.

`__NVIC_PRIO_BITS = 2`, следовательно software model имеет четыре уровня приоритета.

Основные external IRQ из закреплённого SDK:

| IRQ | Источник |
|---:|---|
| 4 | BB/baseband |
| 5 | KSCAN |
| 6 | RTC |
| 10 | WDT |
| 11 | UART0 |
| 12 / 13 | I2C0 / I2C1 |
| 14 / 15 | SPI0 / SPI1 |
| 16 | GPIO |
| 17 | UART1 |
| 18 | SPIF |
| 19 | DMAC |
| 20..25 | TIMER1..TIMER6 |
| 28 | AES |
| 29 | ADCC |
| 30 | QDEC |
| 31 | RNG |

Не назначать application handlers на незаявленные номера без проверки silicon/ROM integration.

## 5. Память

Visible SRAM из vendor headers:

```text
0x1FFF0000 .. 0x1FFFFFFF   64 KiB
```

Известные SDK/ROM области:

| Адрес | Назначение |
|---:|---|
| `0x1FFF0000` | ROM SRAM jump table |
| `0x1FFF0400` | ROM/global config |
| `0x1FFFD000` | jump-table mirror |
| `0x1FFFD400` | global-config mirror |
| `0x1FFFE000` | ROM heap |
| `0x1FFFFC00` | ROM DWC buffer |

SDK bank bases: SRAM0 `0x1FFF0000`, SRAM1 `0x1FFF4000`, SRAM2 `0x1FFF8000`.

### Актуальный linker layout Test-DPLS

Source of truth: [`firmware/targets/phy6252/scatter_load.sct`](../firmware/targets/phy6252/scatter_load.sct) и [`phy6252.ld`](../firmware/targets/phy6252/phy6252.ld).

| Region | Start | Size | Назначение |
|---|---:|---:|---|
| `JUMP_TABLE` | `0x1FFF0000` | `0x400` | ROM jump table |
| `GOLBAL_CONFIG` | `0x1FFF0400` | `0x400` | ROM/global config |
| `ER_IROM1` | `0x1FFF1838` | `0x77C8` | retained SRAM code/data |
| `ER_IROM2` | `0x1FFFC000` | `0x4000` | RF PHY routines |
| application XIP | `0x11020000` | `0x1C000` | `0x11020000..0x1103BFFF` |
| SNV filesystem | `0x1103C000` | `0x3000` | 3 × 4 KiB persistent sectors |
| factory identity | `0x1103F000` | `0x1000` | immutable-by-policy production record |

Старое значение XIP `0x20000` больше не актуально: оно пересекалось бы с SNV/factory data. Linker теперь обязан дать overflow до `0x1103C000`, а не молча занять persistent sectors.

Factory sector не является OTP/eFuse: полный chip erase физически стирает его. После chip erase прибор обязан пройти provisioning заново.

## 6. Peripheral register bases

Выбранные bases из vendor headers:

| Peripheral | Base |
|---|---:|
| PCR | `0x40000000` |
| TIMER block | `0x40001000` |
| WDT | `0x40002000` |
| IOMUX | `0x40003800` |
| UART0 | `0x40004000` |
| I2C0 / I2C1 | `0x40005000` / `0x40005800` |
| SPI0 / SPI1 | `0x40006000` / `0x40007000` |
| GPIO | `0x40008000` |
| UART1 | `0x40009000` |
| CACHE | `0x4000C000` |
| SPIF controller | `0x4000C800` |
| KSCAN | `0x4000D0C0` |
| PWM | `0x4000E000` |
| AON | `0x4000F000` |
| DMAC | `0x40010000` |
| ADCC | `0x40050000` |
| ADC channel data | `0x40050400` |

Это отражение vendor headers, а не полный silicon register manual. Неиспользуемые поля считать недокументированными, пока нет отдельного подтверждения.

## 7. Power/sleep integration

Проверенные project-specific условия SDK 3.1.2:

1. Текущий memory layout использует `hal_pwrmgr_RAM_retention(RET_SRAM0|RET_SRAM1|RET_SRAM2)`; недостаточный retention приводил к warm reboot/reset loop после wakeup.
2. При `USE_FS=1` filesystem должен быть смонтирован через `hal_fs_init(0x1103C000, 3)` до SNV access.
3. Watchdog feed идёт из interrupt path, поэтому сам по себе WDT не является доказательством liveness application task.
4. `hal_pwrmgr_lock(MOD_USR1)` удерживается во время опасного test mode, чтобы sleep/wakeup не перепрограммировал GPIO под активным силовым выходом.
5. `CFG_HCLK_DYNAMIC_CHANGE=0` — часть проверенного target и меняется только вместе с повторной hardware validation.

Конкретный low-frequency clock source не фиксируется здесь без прямого подтверждения current target configuration.

## 8. ADC

Не все analog-capable pads являются независимыми single-ended channels: часть выводов разделяет differential pairs/PGA/clock-related functions.

Revision 2 Test-DPLS использует четыре входа:

| Измерение | Pin | SDK enum |
|---|---|---|
| +1 | P20 | `ADC_CH3P_P20` |
| +2 | P15 | `ADC_CH3N_P15` |
| +Т | P24 | `ADC_CH2N_P24` |
| reserve | P23 | `ADC_CH1P_P23` |

P11 не оцифровывается: он используется зелёным каналом RGB.

Target запускает ADC через `hal_adc_start(INTERRUPT_MODE)`. `hal_adc_value_cal()` тянет software floating point; ADC/fp objects оставлены в XIP, чтобы не расходовать retained `ER_IROM1`.

Требования и acceptance: [live-voltage-requirements.md](live-voltage-requirements.md).

## 9. GPIO revision 2

Source of truth: [`firmware/phy6252/dpls_board.h`](../firmware/phy6252/dpls_board.h).

| Pin | Signal | Назначение |
|---|---|---|
| P31 / P32 / P33 | `ISO_1/2/T` | isolation outputs |
| P14 / P16 / P17 | `KZ_1/2/T` | short outputs |
| P20 | `PORT1_ADC` | +1 voltage |
| P15 | `PORT2_ADC` | +2 voltage |
| P24 | `PORT_T_ADC` | +Т voltage |
| P23 | `VCAP_ADC` | reserve voltage |
| P07 / P11 / P18 | RGB | red / green / blue |
| P34 | `FACTORY_RESET` | physical reset input |

P16/P17 на уровне silicon также относятся к 32.768-kHz crystal-capable pins. В текущей board configuration они намеренно заняты KZ outputs, поэтому crystal assumptions нельзя добавлять без hardware review.

## 10. Factory MAC и BLE identity

Vendor SDK содержит `check_chip_mAddr()` / `g_chipMAddr` и factory MAC storage в служебной flash-области. Test-DPLS использует этот MAC как public BLE address только если SDK сообщает валидное значение.

Если production batch не имеет пригодного factory public MAC, factory provisioning должен записать BLE static-random address в отдельный factory record.

Firmware **не** генерирует BLE address при boot и **не** использует SNV `0x82` как fallback.

Identity material:

- полный 32-битный `serial_number`;
- IRK / CSRK;
- hardware revision;
- optional static-random BLE address;
- CRC;

находится в factory record `0x1103F000`. Подробно: [factory-identity.md](factory-identity.md).

## 11. SPIF и flash

SPIF controller base: `0x4000C800`.

Production firmware использует vendor flash driver. Direct register access допустим только для diagnostics или изменений, проверенных относительно XIP/cache/locking behavior.

Factory provisioning выполняется отдельно от application image:

```text
rdwr_phy62x2.py ... we 0x3F000 factory.bin
```

Не использовать `wh` для standalone factory HEX: эта операция строит application segment table/header.

## 12. ROM ABI

PHY6252 зависит не только от ARMv6-M ISA, но и от фиксированного ROM ABI.

Target линкует SDK-specific `misc/bb_rom_sym_m0.txt`, где определены absolute ROM symbols: crypto, division helpers, string/memory helpers, BLE LL/HCI и ROM handlers.

Практическое правило: **не смешивать** ROM symbol map, prebuilt BLE/RF libraries и jump table разных SDK revisions без отдельной migration review.

## 13. Build contract

[`firmware/targets/phy6252/test-dpls.cproject.yml`](../firmware/targets/phy6252/test-dpls.cproject.yml) закрепляет, среди прочего:

```text
PHY_MCU_TYPE = MCU_BUMBEE_M0
CFG_SLEEP_MODE = PWR_MODE_SLEEP
CFG_HCLK_DYNAMIC_CHANGE = 0
MTU_SIZE = 247
-fshort-enums
-funsigned-char
CMSIS CORE
ARM Device Startup
bb_rom_sym_m0.txt
```

Поддерживаются два независимых target пути:

- Keil MDK / Arm Compiler 6 через CMSIS-Toolbox/cbuild;
- GNU Arm Embedded.

Оба используют одни project-owned sources и pinned SDK 3.1.2.

## 14. Приоритет источников

При расхождении сведений использовать следующий порядок:

1. pinned SDK 3.1.2 + его libraries + `bb_rom_sym_m0.txt` для текущего ABI;
2. project target/scatter/MAP + hardware validation для реальной интеграции;
3. vendor headers/drivers того же pinned SDK;
4. Armv6-M Architecture Reference Manual / Cortex-M0 Generic User Guide для core ISA/programmer model;
5. PHY6252 product specification;
6. distributor/marketing pages только как подсказки для поиска.

## 15. Открытые пробелы документации

Публично не найдено или недостаточно подтверждено:

- отдельный BUMBee M0 Technical Reference Manual;
- отдельный BUMBee ISA manual;
- CPU errata/SDEN именно для PHY6252/BUMBee M0;
- документ о происхождении/лицензировании CPU RTL;
- полный официальный PHY6252 register reference уровня обычного MCU reference manual.

Поэтому этот документ осознанно объединяет Arm core manuals, pinned SDK, ROM symbol map, project linker configuration и hardware findings.

## 16. Checklist миграции SDK

Для будущего SDK candidate, включая 3.1.5, отдельно сравнить:

1. `components/arch/cm0/core_bumbee_m0.h`;
2. `components/inc/mcu.h`, `mcu_phy_bumbee.h`, `bus_dev.h`;
3. IRQ map;
4. `misc/bb_rom_sym_m0.txt` и `misc/jump_table.c`;
5. ADC/GPIO/flash/pwrmgr drivers;
6. `rf.lib` и `ble_host.lib`;
7. startup/linker assumptions;
8. release notes;
9. host tests, Keil/GCC target builds и полный hardware bring-up.

Pinned production SDK не обновлять побочным эффектом несвязанного refactor.
