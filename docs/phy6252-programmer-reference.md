# PHY6252 / BUMBee M0: programmer reference

Рабочий low-level reference для Test-DPLS. Здесь зафиксированы только свойства PHY6252, на которые реально опираются target, linker, firmware и hardware bring-up.

## 1. CPU

Для компилятора PHY6252 в этом проекте является **ARMv6-M / Cortex-M0 software target**.

Основание в закреплённом vendor SDK 3.1.2:

- target `MCU_BUMBEE_M0`;
- `core_bumbee_m0.h` описывает Cortex-M0 processor/core peripherals;
- CMSIS wrapper использует `core_cm0.h` и `system_ARMCM0.h`;
- `__NVIC_PRIO_BITS = 2`;
- Test-DPLS собирается как M0 и использует ROM symbol map `bb_rom_sym_m0.txt`.

Это подтверждает ISA/CMSIS/ABI-совместимость программного target, но не происхождение RTL.

## 2. Закреплённый SDK

Production target использует **PHY62XX SDK 3.1.2**.

Source of truth: [`firmware/sdk/phy6252-sdk.env`](../firmware/sdk/phy6252-sdk.env).

```text
repository: xuhongv/PHY6252_6222_SDK
commit:     b7202ee56e8d316ea3451dd61266f609e6a676e8
directory:  firmware/sdk/PHY62XX_SDK_3.1.2
```

Обновление SDK — отдельная миграция: могут измениться ROM ABI, BLE/RF libraries, jump table, flash/ADC/power behavior и linker assumptions.

## 3. ISA baseline

Codegen: ARMv6-M, Thumb, Cortex-M0.

Можно рассчитывать на стандартный M0 subset: integer arithmetic, shifts, multiply, loads/stores, `PUSH/POP`, branches, `BL/BX`, `SVC/BKPT`, `MRS/MSR`, barriers и `WFI/WFE/SEV`.

Не считать baseline возможностями M3/M4+: hardware divide, DSP, FPU, `BASEPRI`, ARMv7-M exclusive-access assumptions.

## 4. Core/IRQ

Programmer model: `R0..R15`, MSP/PSP, xPSR, PRIMASK, CONTROL. SDK использует стандартные NMI, HardFault, SVCall, PendSV, SysTick.

`__NVIC_PRIO_BITS = 2`.

Ключевые external IRQ: BB=4, RTC=6, WDT=10, UART0=11, GPIO=16, SPIF=18, DMAC=19, TIMER1..6=20..25, AES=28, ADCC=29, RNG=31.

## 5. Память и linker

Visible SRAM:

```text
0x1FFF0000 .. 0x1FFFFFFF   64 KiB
```

Актуальные project regions:

| Region | Start | Size |
|---|---:|---:|
| `JUMP_TABLE` | `0x1FFF0000` | `0x400` |
| `GOLBAL_CONFIG` | `0x1FFF0400` | `0x400` |
| `ER_IROM1` | `0x1FFF1838` | `0x77C8` |
| `ER_IROM2` | `0x1FFFC000` | `0x4000` |
| XIP linker window | `0x11020000` | `0x20000` |
| SNV filesystem | `0x1103C000` | `0x3000` |

Почему linker window снова `0x20000`: это геометрия реально работающего release 1.4.0. Сужение до `0x1C000` было частью RC6 boot regression и больше не используется.

При этом **фактический application HEX не имеет права занимать SNV**. `tools/build_firmware.sh` разбирает готовый Intel HEX и падает, если data record пересекает `0x1103C000..0x1103FFFF`.

То есть разделены два понятия:

- linker geometry, от которой зависит boot/image layout;
- реальные записываемые bytes, которые обязаны заканчиваться до SNV.

Отдельного project factory sector в конце flash нет.

## 6. Peripheral bases

Выбранные bases из vendor headers:

| Peripheral | Base |
|---|---:|
| PCR | `0x40000000` |
| TIMER | `0x40001000` |
| WDT | `0x40002000` |
| IOMUX | `0x40003800` |
| UART0 | `0x40004000` |
| GPIO | `0x40008000` |
| CACHE | `0x4000C000` |
| SPIF | `0x4000C800` |
| AON | `0x4000F000` |
| DMAC | `0x40010000` |
| ADCC | `0x40050000` |

## 7. Power/sleep integration

Проверенные условия SDK 3.1.2:

1. `hal_pwrmgr_RAM_retention(RET_SRAM0|RET_SRAM1|RET_SRAM2)` обязателен для текущего layout.
2. При `USE_FS=1` filesystem монтируется через `hal_fs_init(0x1103C000, 3)` до SNV access.
3. `hal_pwrmgr_lock(MOD_USR1)` удерживается только во время активного силового test mode.
4. `CFG_HCLK_DYNAMIC_CHANGE=0` меняется только вместе с новой hardware validation.

## 8. ADC revision 2

| Измерение | Pin | SDK enum |
|---|---|---|
| +1 | P20 | `ADC_CH3P_P20` |
| +2 | P15 | `ADC_CH3N_P15` |
| +Т | P24 | `ADC_CH2N_P24` |
| reserve | P23 | `ADC_CH1P_P23` |

Target использует `hal_adc_start(INTERRUPT_MODE)`. Каналы запускаются последовательно, чтобы не зависеть от starvation внутри vendor ADC interrupt path.

## 9. GPIO revision 2

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

## 10. BLE identity

Используется аппаратно проверенный путь release 1.4.0:

1. `check_chip_mAddr()` / `g_chipMAddr` читают заводской PHY6252 MAC через vendor decoder;
2. если заводского MAC нет — читается SNV `0x82`;
3. если SNV пуст — MAC генерируется один раз и сохраняется;
4. IRK/CSRK читаются/создаются в BLE SNV;
5. `HCI_EXT_SetBDADDRCmd()` вызывается **до** `GAPRole_StartDevice()`;
6. после `GAPROLE_STARTED` identity синхронизируется с GAP/resolving list.

Firmware не делает raw `hal_flash_read()` произвольного project factory sector в boot path.

Advertising не зависит от успешности отдельного provisioning. При сбое identity preparation устройство остаётся видимым как `Test-DPLS-0000`, что позволяет диагностировать живую плату.

## 11. Flash/programmer

Production build создаёт один Intel HEX.

Прошивка:

```text
rdwr_phy62x2.py ... wh application.hex
```

Отдельных `.factory.bin`, `we 0x3F000`, merge factory HEX и персонализированных build artifacts нет.

Полный chip erase стирает SNV, после чего runtime заново создаёт fallback BLE identity/keys/settings state по обычным правилам.

## 12. ROM ABI

PHY6252 зависит не только от ARMv6-M ISA, но и от фиксированного ROM ABI.

Target линкует SDK-specific `misc/bb_rom_sym_m0.txt`, где определены absolute ROM symbols: crypto, division helpers, string/memory helpers, BLE LL/HCI и ROM handlers.

Практическое правило: **не смешивать** ROM symbol map, prebuilt BLE/RF libraries и jump table разных SDK revisions без отдельной migration review.
