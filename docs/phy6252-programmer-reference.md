# PHY6252 / BUMBee M0 programmer reference

Рабочий low-level reference для Test-DPLS. Он фиксирует то, на что реально опираются compiler target, linker, firmware и hardware bring-up.

## 1. CPU: что известно

Для компилятора PHY6252 в этом проекте рассматривается как **ARMv6-M / Cortex-M0 software target**.

Основание в закреплённом vendor SDK:

- target: `MCU_BUMBEE_M0`;
- `core_bumbee_m0.h` описывает Cortex-M0 processor/core peripherals;
- wrapper использует CMSIS `core_cm0.h` и `system_ARMCM0.h`;
- `__NVIC_PRIO_BITS = 2`;
- target Test-DPLS собирается как M0 и использует ROM symbol map `bb_rom_sym_m0.txt`.

Это доказывает **ISA/CMSIS/ABI-совместимость программного target**, но не происхождение RTL. Публичных материалов недостаточно, чтобы называть ядро либо «клоном Cortex-M0», либо подтверждённым лицензированным Cortex-M0 IP.

Корректная формулировка:

> PHY6252 использует PhyPlus BUMBee M0 target с Cortex-M0 / ARMv6-M-совместимым программным интерфейсом; происхождение CPU RTL публично не установлено.

## 2. SDK pin

Production target закреплён на **PHY62XX SDK 3.1.2**.

Source of truth: [`firmware/sdk/phy6252-sdk.env`](../firmware/sdk/phy6252-sdk.env).

```text
repository: xuhongv/PHY6252_6222_SDK
commit:     b7202ee56e8d316ea3451dd61266f609e6a676e8
directory:  firmware/sdk/PHY62XX_SDK_3.1.2
```

Полный SDK не vendored: `tools/fetch_phy6252_sdk.sh` загружает и проверяет точный commit.

Обновление SDK — отдельная миграция. Даже при неизменной CPU ISA могут измениться ROM ABI, jump table, BLE libraries, power manager, flash/ADC behavior и linker assumptions.

## 3. ISA baseline

Codegen target: ARMv6-M / Cortex-M0, Thumb.

Безопасно рассчитывать на стандартный Cortex-M0 subset:

- integer arithmetic/logical operations;
- shifts/rotates and multiply;
- byte/halfword/word loads/stores;
- `PUSH` / `POP`;
- branches, `BL`, `BX`, допустимые ARMv6-M формы `BLX`;
- `SVC`, `BKPT`;
- `MRS` / `MSR` для доступных special registers;
- `DMB`, `DSB`, `ISB`;
- `WFI`, `WFE`, `SEV`.

Не принимать за baseline возможности M3/M4+:

- аппаратные `SDIV` / `UDIV`;
- `CLZ` как M0 instruction;
- DSP extension;
- FPU;
- `BASEPRI` / `FAULTMASK`;
- MemManage/BusFault/UsageFault model;
- ARMv7-M exclusive-access assumptions (`LDREX` / `STREX`).

ROM предоставляет `__aeabi_idiv*` / `__aeabi_uidiv*`, поэтому отсутствие hardware divide не означает отсутствие C integer division.

Hand-written assembly собирать именно под Cortex-M0 / ARMv6-M, не под generic Thumb-2.

## 4. Core registers and exceptions

Programmer model:

| Group | Registers |
|---|---|
| general | `R0..R12` |
| stack | `R13/SP`, MSP, PSP |
| link | `R14/LR` |
| PC | `R15/PC` |
| status | `xPSR` (APSR/IPSR/EPSR) |
| control | `PRIMASK`, `CONTROL` |

Standard core exceptions used by SDK include NMI, HardFault, SVCall, PendSV and SysTick.

`__NVIC_PRIO_BITS = 2`, therefore the software model exposes four priority levels. SDK convenience priorities map 0..3 from realtime to application/thread level.

### External IRQ map used by SDK

| IRQ | Source |
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

## 5. Memory map

Visible SRAM range from vendor headers:

```text
0x1FFF0000 .. 0x1FFFFFFF   (64 KiB)
```

Known SDK/ROM areas:

| Address | Purpose |
|---:|---|
| `0x1FFF0000` | ROM SRAM jump table |
| `0x1FFF0400` | ROM/global config |
| `0x1FFFD000` | jump-table mirror |
| `0x1FFFD400` | global-config mirror |
| `0x1FFFE000` | ROM heap |
| `0x1FFFFC00` | ROM DWC buffer |

SDK bank bases: SRAM0 `0x1FFF0000`, SRAM1 `0x1FFF4000`, SRAM2 `0x1FFF8000`.

### Test-DPLS linker layout

Source: [`firmware/targets/phy6252/scatter_load.sct`](../firmware/targets/phy6252/scatter_load.sct).

| Region | Start | Size | Purpose |
|---|---:|---:|---|
| `JUMP_TABLE` | `0x1FFF0000` | `0x400` | ROM jump table |
| `GOLBAL_CONFIG` | `0x1FFF0400` | `0x400` | ROM/global config |
| `ER_IROM1` | `0x1FFF1838` | `0x77C8` | retained SRAM code/data |
| `ER_IROM2` | `0x1FFFC000` | `0x4000` | RF PHY routines |
| `ER_ROM_XIP` | `0x11020000` | `0x20000` | XIP application/code |

SPIF memory window base is `0x11000000`. Project XIP begins at `0x11020000`.

SNV filesystem is mounted at `0x1103C000` for three sectors. Full flash erase clears settings, BLE identity/auth state, calibration and journal; normal application update preserves SNV.

Retained SRAM is constrained. Large static buffers/floating-point support should not be moved into retained regions without checking the MAP output.

## 6. Peripheral register bases

Selected programmer-facing bases from vendor headers:

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

Эта таблица — отражение vendor headers, не замена полного silicon register manual. Неиспользуемые vendor driver поля считать недостаточно документированными.

## 7. Power/sleep integration

Проверенные project-specific условия SDK 3.1.2:

1. Для текущей memory layout нужен `hal_pwrmgr_RAM_retention(RET_SRAM0|RET_SRAM1|RET_SRAM2)`; недостаточный retention приводит к warm reboot/reset-loop после wakeup.
2. При `USE_FS=1` необходимо выполнить `hal_fs_init`, иначе SNV фактически недоступен.
3. Watchdog feed происходит из interrupt path, поэтому сам по себе WDT не доказывает liveness application task.
4. `hal_pwrmgr_lock(MOD_USR1)` удерживается, пока энергизован опасный test mode, чтобы sleep/wakeup не перепрограммировал GPIO под активным силовым выходом.
5. `CFG_HCLK_DYNAMIC_CHANGE=0` является частью проверенного target и меняется только с повторной hardware validation.

Подробнее: [`firmware/README.md`](../firmware/README.md).

## 8. ADC

Vendor SDK exposes multiple analog-capable pads, but не все AIO следует считать независимыми single-ended каналами: часть выводов разделяет differential pairs, PGA и 32.768 kHz crystal functions.

Production Test-DPLS revision 2 использует четыре входа:

| Measurement | Pin | SDK enum |
|---|---|---|
| +1 | P20 | `ADC_CH3P_P20` |
| +2 | P15 | `ADC_CH3N_P15` |
| +Т | P24 | `ADC_CH2N_P24` |
| reserve | P23 | `ADC_CH1P_P23` |

P11, соседний по differential pair, намеренно не оцифровывается — это зелёный канал RGB.

Target запускает ADC через `hal_adc_start(INTERRUPT_MODE)`. `hal_adc_value_cal()` использует software floating point; ADC/fp objects вынесены в XIP, чтобы не расходовать retained `ER_IROM1`.

Требования к live telemetry и acceptance: [live-voltage-requirements.md](live-voltage-requirements.md).

## 9. GPIO revision 2

Source of truth: [`firmware/phy6252/dpls_board.h`](../firmware/phy6252/dpls_board.h).

| Pin | Signal | Function |
|---|---|---|
| P31 / P32 / P33 | `ISO_1/2/T` | isolation outputs |
| P14 / P16 / P17 | `KZ_1/2/T` | short outputs |
| P20 | `PORT1_ADC` | +1 voltage |
| P15 | `PORT2_ADC` | +2 voltage |
| P24 | `PORT_T_ADC` | +Т voltage |
| P23 | `VCAP_ADC` | reserve voltage |
| P07 / P11 / P18 | RGB | red / green / blue |
| P34 | `FACTORY_RESET` | physical reset input |

P16/P17 overlap 32.768 kHz crystal-capable pins at silicon level; current board configuration intentionally uses them as KZ outputs, so crystal assumptions must not be introduced casually.

## 10. SPIF and flash

SPIF controller base: `0x4000C800`.

Useful register groups in `AP_SPIF_TypeDef` cover configuration/read/write instructions, device delays/size, DMA trigger, interrupt status/mask, write-protection ranges, indirect read/write control and direct flash commands.

Production code should use the vendor flash driver. Direct register access is reserved for diagnostics or changes reviewed against XIP/cache/lock behavior.

## 11. ROM ABI

PHY6252 firmware depends on a fixed ROM ABI in addition to ARMv6-M ISA.

The target links the SDK-specific `misc/bb_rom_sym_m0.txt`, which exports absolute ROM symbols including crypto, division helpers, string/memory helpers, BLE LL/HCI services and ROM handlers.

Practical rule: **never mix** ROM symbol map, prebuilt BLE/RF libraries or jump-table implementation from different SDK revisions without an explicit migration review.

## 12. Build contract

[`firmware/targets/phy6252/test-dpls.cproject.yml`](../firmware/targets/phy6252/test-dpls.cproject.yml) defines the target assumptions, including:

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

Target builds are maintained for:

- Keil MDK / Arm Compiler 6 via CMSIS-Toolbox/cbuild;
- GNU Arm Embedded for an independent compiler/linker path.

Both consume the same project-owned firmware sources and pinned SDK.

## 13. Source precedence

When sources disagree, use this order:

1. pinned SDK 3.1.2 + its libraries + `bb_rom_sym_m0.txt` for the current ABI;
2. project target/scatter/MAP + hardware validation for real integration;
3. vendor register headers/drivers from that pinned SDK;
4. Armv6-M Architecture Reference Manual / Cortex-M0 Generic User Guide for core ISA/programmer model;
5. PHY6252 product specification;
6. distributor/marketing pages only as discovery hints.

## 14. Open documentation gaps

Publicly unavailable or not yet found:

- BUMBee M0 Technical Reference Manual;
- separate BUMBee ISA manual;
- CPU errata/SDEN specific to PHY6252/BUMBee M0;
- document establishing CPU RTL/IP-license provenance;
- complete official PHY6252 register reference comparable to a conventional MCU reference manual.

Therefore this reference intentionally combines Arm core manuals, pinned PhyPlus SDK headers/drivers, ROM symbol map, project linker configuration and hardware findings.

## 15. SDK migration checklist

For any future SDK candidate (including 3.1.5), compare at least:

1. `components/arch/cm0/core_bumbee_m0.h`;
2. `components/inc/mcu.h`, `mcu_phy_bumbee.h`, `bus_dev.h`;
3. IRQ map;
4. `misc/bb_rom_sym_m0.txt` and `misc/jump_table.c`;
5. ADC/GPIO/flash/pwrmgr drivers;
6. `rf.lib` and `ble_host.lib`;
7. startup/linker assumptions;
8. release notes;
9. host tests, both target compilers and full hardware bring-up.

Do not upgrade the pinned production SDK as a side effect of an unrelated refactor.

## Primary references

### Project

- [`firmware/sdk/phy6252-sdk.env`](../firmware/sdk/phy6252-sdk.env)
- [`firmware/README.md`](../firmware/README.md)
- [`firmware/phy6252/dpls_board.h`](../firmware/phy6252/dpls_board.h)
- [`firmware/targets/phy6252/test-dpls.cproject.yml`](../firmware/targets/phy6252/test-dpls.cproject.yml)
- [`firmware/targets/phy6252/scatter_load.sct`](../firmware/targets/phy6252/scatter_load.sct)

### Vendor SDK 3.1.2

- `core_bumbee_m0.h`
- `mcu.h`
- `mcu_phy_bumbee.h`
- `bus_dev.h`
- ADC/GPIO/pwrmgr/flash drivers
- `misc/bb_rom_sym_m0.txt`

Pinned upstream commit: `b7202ee56e8d316ea3451dd61266f609e6a676e8` in `xuhongv/PHY6252_6222_SDK`.

### Arm

- Armv6-M Architecture Reference Manual (DDI0419)
- Cortex-M0 Devices Generic User Guide (DUI0497)
