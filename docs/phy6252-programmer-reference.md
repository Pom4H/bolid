# PHY6252 / BUMBee M0: programmer reference

Low-level reference для Test-DPLS. Здесь зафиксированы только текущие свойства PHY6252, на которые опираются target, linker, firmware и hardware bring-up.

## CPU / SDK

Production target — ARMv6-M / Cortex-M0 software target из **PHY62XX SDK 3.1.2**.

Source of truth: [`firmware/sdk/phy6252-sdk.env`](../firmware/sdk/phy6252-sdk.env).

```text
repository: xuhongv/PHY6252_6222_SDK
commit:     b7202ee56e8d316ea3451dd61266f609e6a676e8
directory:  firmware/sdk/PHY62XX_SDK_3.1.2
```

Target использует SDK ROM symbol map `bb_rom_sym_m0.txt`. Не смешивать ROM map, prebuilt BLE/RF libraries и jump table разных SDK revisions.

## ISA baseline

Codegen: ARMv6-M, Thumb, Cortex-M0. Baseline включает стандартные integer/load-store/branch/system инструкции M0. Не предполагать hardware divide, DSP, FPU, `BASEPRI` или ARMv7-M exclusive access.

## Memory map

Visible SRAM:

```text
0x1FFF0000 .. 0x1FFFFFFF   64 KiB
```

Текущие project regions:

| Region | Start | Size |
|---|---:|---:|
| `JUMP_TABLE` | `0x1FFF0000` | `0x400` |
| `GOLBAL_CONFIG` | `0x1FFF0400` | `0x400` |
| `ER_IROM1` | `0x1FFF1838` | `0x77C8` |
| `ER_IROM2` | `0x1FFFC000` | `0x4000` |
| XIP linker window | `0x11020000` | `0x20000` |
| SNV filesystem | `0x1103C000` | `0x3000` |

Linker window и реально записываемые bytes — разные ограничения. `tools/build_firmware.sh` валидирует готовый Intel HEX и отклоняет любой data record, пересекающий `0x1103C000..0x1103FFFF`.

## Peripheral bases

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

## Power / sleep

- `hal_pwrmgr_RAM_retention(RET_SRAM0|RET_SRAM1|RET_SRAM2)` нужен текущему layout;
- при `USE_FS=1` SNV монтируется через `hal_fs_init(0x1103C000, 3)`;
- `hal_pwrmgr_lock(MOD_USR1)` удерживается во время активного силового test mode;
- `CFG_HCLK_DYNAMIC_CHANGE=0` — часть target configuration.

## ADC revision 2

| Измерение | Pin | SDK enum |
|---|---|---|
| +1 | P20 | `ADC_CH3P_P20` |
| +2 | P15 | `ADC_CH3N_P15` |
| +Т | P24 | `ADC_CH2N_P24` |
| reserve | P23 | `ADC_CH1P_P23` |

Каналы запускаются последовательно через `hal_adc_start(INTERRUPT_MODE)`.

## GPIO revision 2

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

## BLE identity / boot

1. `check_chip_mAddr()` / `g_chipMAddr` читают заводской PHY6252 MAC;
2. fallback MAC хранится в SNV `0x82`;
3. если MAC отсутствует, он генерируется один раз и сохраняется;
4. IRK/CSRK хранятся в BLE SNV;
5. `HCI_EXT_SetBDADDRCmd()` вызывается до `GAPRole_StartDevice()`;
6. после `GAPROLE_STARTED` запускается advertising;
7. ошибка identity preparation не должна блокировать advertising.

Boot path не читает произвольный project factory sector raw-доступом.

## ROM UART programmer

Production build создаёт один Intel HEX. Прошивка выполняется штатной операцией `wh`.

ROM-entry protocol, используемый programmer:

```text
9600 baud
reset/test-mode via control lines (если подключены)
UXTDWU → cmd>>:
переключение на рабочую baud rate
flash erase/program
```

Обычный wrapper не ждёт Enter:

```sh
tools/flash_firmware.sh tmp/test-dpls.hex
```

Автоматический стенд с заведёнными RTS/DTR использует:

```sh
bash tools/flash_firmware_agent.sh tmp/test-dpls.hex
```

Если reset/test-mode физически не управляются USB-UART/fixture, UART-команда сама по себе не может заменить аппаратный вход в ROM bootloader.
