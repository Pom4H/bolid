#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one exact match, got {count}: {old[:80]!r}")
    write(path, text.replace(old, new, 1))


def sub_once(path: str, pattern: str, replacement: str, flags: int = 0) -> None:
    text = read(path)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"{path}: expected one regex match, got {count}: {pattern[:100]!r}")
    write(path, updated)


board = r'''#ifndef DPLS_BOARD_H
#define DPLS_BOARD_H

#include "gpio.h"

/* Target pin assignment for Test-DPLS hardware revision 2.
 *
 * Control logic is 3.3 V, active-high. All control outputs have hardware
 * pull-downs, therefore all zeroes are the fail-safe "Norma" state.
 *
 * Isolation switches (normally closed):
 *   ISO_1 — main port "+1"   ISO_2 — main port "+2"   ISO_T — tap "+T"
 * Short shunts (normally open):
 *   KZ_1 — "+1"             KZ_2 — "+2"             KZ_T — "+T"
 */
#define DPLS_PIN_ISO_1 GPIO_P31
#define DPLS_PIN_ISO_2 GPIO_P32
#define DPLS_PIN_ISO_T GPIO_P33
#define DPLS_PIN_KZ_1 GPIO_P14
#define DPLS_PIN_KZ_2 GPIO_P16
#define DPLS_PIN_KZ_T GPIO_P17

/* Four independent single-ended ADC inputs of the PHY6252 ADC multiplexer.
 * Every external DPLS input must be connected through its own >=1 Mohm,
 * 0..30 V divider/protection path. The firmware scans one channel at a time.
 *
 * SDK channel aliases:
 *   P20 -> ADC_CH9, P15 -> ADC_CH4, P11 -> ADC_CH0, P23 -> ADC_CH1.
 */
#define DPLS_PIN_PORT1_ADC GPIO_P20
#define DPLS_PIN_PORT2_ADC GPIO_P15
#define DPLS_PIN_PORT_T_ADC GPIO_P11
#define DPLS_PIN_VCAP_ADC GPIO_P23
/* Backward-compatible name for the legacy DPLS voltage channel. */
#define DPLS_PIN_LINE_ADC DPLS_PIN_PORT1_ADC

/* Password reset — physical access only. */
#define DPLS_PIN_FACTORY_RESET GPIO_P24

/* The production device has one status light guide. It is used for identify
 * indication; test-mode state is shown in the operator application. */
#define DPLS_PIN_STATUS_LED GPIO_P07

/* P18, P34 and P00 are not used by the target logic. */

#endif
'''
write("Firmware/phy6252/dpls_board.h", board)

app = "Firmware/phy6252/dpls_phy6252_app.c"
sub_once(
    app,
    r'#define DPLS_HW_REVISION 1u[^\n]*',
    '#define DPLS_HW_REVISION 2u /* four independent voltage inputs: +1, +2, +T, reserve */',
)
replace_once(
    app,
    "#define DPLS_ADC_NEED_LINE 0x01u\n#define DPLS_ADC_NEED_VCAP 0x02u",
    "#define DPLS_ADC_NEED_PORT1 0x01u\n"
    "#define DPLS_ADC_NEED_PORT2 0x02u\n"
    "#define DPLS_ADC_NEED_PORT_T 0x04u\n"
    "#define DPLS_ADC_NEED_VCAP 0x08u\n"
    "#define DPLS_ADC_NEED_ALL (DPLS_ADC_NEED_PORT1 | DPLS_ADC_NEED_PORT2 | \\\n"
    "                           DPLS_ADC_NEED_PORT_T | DPLS_ADC_NEED_VCAP)",
)
sub_once(
    app,
    r'static dpls_calib_t line_calib;\nstatic dpls_calib_t vcap_calib;\n.*?static uint8_t adc_decimate;',
    '''static dpls_calib_t line_calib;
static dpls_calib_t vcap_calib;
static uint16_t line_window[DPLS_ADC_WINDOW];
static uint16_t port2_window[DPLS_ADC_WINDOW];
static uint16_t port_t_window[DPLS_ADC_WINDOW];
static uint16_t vcap_window[DPLS_ADC_WINDOW];
static uint8_t line_window_count, line_window_pos;
static uint8_t port2_window_count, port2_window_pos;
static uint8_t port_t_window_count, port_t_window_pos;
static uint8_t vcap_window_count, vcap_window_pos;
static volatile uint16_t cached_line_mv;
static volatile uint16_t cached_port2_mv;
static volatile uint16_t cached_port_t_mv;
static volatile uint16_t cached_vcap_mv;
static volatile bool adc_busy;
static uint8_t adc_pending; /* DPLS_ADC_NEED_* bits still owed this cycle */
static uint8_t adc_decimate;''',
    re.S,
)
sub_once(
    app,
    r'static void mode_outputs_off\(void\)\n\{.*?\n\}',
    '''static void mode_outputs_off(void)
{
    hal_gpio_write(DPLS_PIN_ISO_1, 0);
    hal_gpio_write(DPLS_PIN_ISO_2, 0);
    hal_gpio_write(DPLS_PIN_ISO_T, 0);
    hal_gpio_write(DPLS_PIN_KZ_1, 0);
    hal_gpio_write(DPLS_PIN_KZ_2, 0);
    hal_gpio_write(DPLS_PIN_KZ_T, 0);
}''',
    re.S,
)
sub_once(
    app,
    r'static bool apply_mode\(void \*context, dpls_mode_t mode\)\n\{.*?\n\}',
    '''static bool apply_mode(void *context, dpls_mode_t mode)
{
    (void)context;
    if (mode > DPLS_MODE_SHORT_T) return false;
    /* Break-before-make: return to the all-safe state first so no two outputs
     * are ever driven together, then assert the single line for this mode. */
    mode_outputs_off();
    switch (mode) {
    case DPLS_MODE_NORMAL: break;
    case DPLS_MODE_OPEN_T:
        hal_gpio_write(DPLS_PIN_ISO_T, 1);
        break;
    case DPLS_MODE_OPEN_MAIN:
        hal_gpio_write(DPLS_PIN_ISO_2, 1);
        break;
    case DPLS_MODE_SHORT_1:
        hal_gpio_write(DPLS_PIN_KZ_1, 1);
        break;
    case DPLS_MODE_SHORT_2:
        hal_gpio_write(DPLS_PIN_KZ_2, 1);
        break;
    case DPLS_MODE_SHORT_T:
        hal_gpio_write(DPLS_PIN_KZ_T, 1);
        break;
    default: return false;
    }
    hardware_mode = mode;
    return true;
}''',
    re.S,
)
sub_once(
    app,
    r'/\* Raw samples captured by the ISR, drained by the OSAL task\. \*/\n.*?static volatile bool line_raw_ready, vcap_raw_ready;',
    '''/* A single raw buffer is enough because channels are converted strictly
 * one at a time. This saves SRAM compared with one MAX_ADC_SAMPLE_SIZE buffer
 * per voltage input. */
static volatile uint16_t adc_raw[MAX_ADC_SAMPLE_SIZE];
static volatile uint8_t adc_raw_size;
static volatile adc_CH_t adc_raw_channel;
static volatile bool adc_raw_ready;''',
    re.S,
)
sub_once(
    app,
    r'static void adc_evt\(adc_Evt_t \*event\)\n\{.*?\n\}',
    '''static void adc_evt(adc_Evt_t *event)
{
    uint8_t i, n;
    if (event->type != HAL_ADC_EVT_DATA) {
        adc_busy = false;
        return;
    }
    n = event->size > MAX_ADC_SAMPLE_SIZE ? MAX_ADC_SAMPLE_SIZE : event->size;
    for (i = 0; i < n; ++i) adc_raw[i] = event->data[i];
    adc_raw_size = n;
    adc_raw_channel = event->ch;
    adc_raw_ready = true;
    /* One-shot mode: the ADC IRQ handler stops the converter after the last
     * channel callback returns, so we only clear our re-entrancy guard. */
    adc_busy = false;
    osal_set_event(task_id, DPLS_PHY6252_ADC_EVT);
}''',
    re.S,
)
sub_once(
    app,
    r'static void adc_kick\(void\)\n\{.*?\n\}',
    '''static void adc_kick(void)
{
    adc_Cfg_t cfg;
    uint8_t channel;
    uint8_t claim;
    if (adc_busy || adc_raw_ready || adc_pending == 0u) return;
    memset(&cfg, 0, sizeof(cfg));
    /* One channel per conversion. P20/P15/P11 are the independent +1/+2/+T
     * voltage paths, P23 is the reserve accumulator. Standard resolution is
     * used because every divider keeps its ADC pin close to or below 1 V. */
    if (adc_pending & DPLS_ADC_NEED_PORT1) {
        channel = ADC_BIT(ADC_CH3P_P20);
        claim = DPLS_ADC_NEED_PORT1;
    } else if (adc_pending & DPLS_ADC_NEED_PORT2) {
        channel = ADC_BIT(ADC_CH3N_P15);
        claim = DPLS_ADC_NEED_PORT2;
    } else if (adc_pending & DPLS_ADC_NEED_PORT_T) {
        channel = ADC_BIT(ADC_CH1N_P11);
        claim = DPLS_ADC_NEED_PORT_T;
    } else {
        channel = ADC_BIT(ADC_CH1P_P23);
        claim = DPLS_ADC_NEED_VCAP;
    }
    cfg.channel = channel;
    cfg.is_continue_mode = FALSE;
    cfg.is_differential_mode = 0u;
    cfg.is_high_resolution = 0u;
    adc_busy = true;
    if (hal_adc_config_channel(cfg, adc_evt) != PPlus_SUCCESS) {
        adc_busy = false;
        return;
    }
    if (hal_adc_start(INTERRUPT_MODE) != PPlus_SUCCESS) {
        (void)hal_adc_stop();
        adc_busy = false;
        return;
    }
    adc_pending = (uint8_t)(adc_pending & (uint8_t)~claim);
}''',
    re.S,
)
sub_once(
    app,
    r'void dpls_phy6252_process_adc\(void\)\n\{.*?\n\}',
    '''void dpls_phy6252_process_adc(void)
{
    if (adc_raw_ready) {
        adc_CH_t ch = adc_raw_channel;
        uint8_t size = adc_raw_size;
        adc_raw_ready = false;
        switch (ch) {
        case ADC_CH9:
            process_adc_channel(ch, adc_raw, size, &line_calib,
                                line_window, &line_window_count, &line_window_pos,
                                &cached_line_mv);
            break;
        case ADC_CH4:
            process_adc_channel(ch, adc_raw, size, &line_calib,
                                port2_window, &port2_window_count, &port2_window_pos,
                                &cached_port2_mv);
            break;
        case ADC_CH0:
            process_adc_channel(ch, adc_raw, size, &line_calib,
                                port_t_window, &port_t_window_count, &port_t_window_pos,
                                &cached_port_t_mv);
            break;
        case ADC_CH1:
            process_adc_channel(ch, adc_raw, size, &vcap_calib,
                                vcap_window, &vcap_window_count, &vcap_window_pos,
                                &cached_vcap_mv);
            break;
        default:
            break;
        }
    }
    /* Start the next channel from task context after consuming the shared raw
     * buffer. A complete four-channel cycle is still initiated every second. */
    adc_kick();
}''',
    re.S,
)
replace_once(
    app,
    '''static uint16_t port1_voltage_mv(void *context)
{
    (void)context;
    return cached_line_mv;
}

static uint16_t reserve_voltage_mv(void *context)
{
    (void)context;
    return cached_vcap_mv;
}''',
    '''static uint16_t port1_voltage_mv(void *context)
{
    (void)context;
    return cached_line_mv;
}

static uint16_t port2_voltage_mv(void *context)
{
    (void)context;
    return cached_port2_mv;
}

static uint16_t port_t_voltage_mv(void *context)
{
    (void)context;
    return cached_port_t_mv;
}

static uint16_t reserve_voltage_mv(void *context)
{
    (void)context;
    return cached_vcap_mv;
}''',
)
sub_once(
    app,
    r'static uint8_t measurement_validity\(void \*context\)\n\{.*?\n\}',
    '''static uint8_t measurement_validity(void *context)
{
    uint8_t flags = 0;
    (void)context;
    if (line_window_count != 0u)
        flags |= DPLS_STATE_PORT_1_VALID | DPLS_STATE_POWER_VALID |
                 DPLS_STATE_AUTOISO_VALID;
    if (port2_window_count != 0u)
        flags |= DPLS_STATE_PORT_2_VALID;
    if (port_t_window_count != 0u)
        flags |= DPLS_STATE_PORT_T_VALID;
    if (vcap_window_count != 0u)
        flags |= DPLS_STATE_RESERVE_VOLTAGE_VALID;
    if (line_calib_from_nv)
        flags |= DPLS_STATE_ADC_CALIBRATED;
    return flags;
}''',
    re.S,
)
# Production hardware has only the single status LED; remove dev-kit mode lamps.
text = read(app)
text, removed_init = re.subn(r'\n    hal_gpio_pin_init\(DPLS_PIN_LED_[A-Z0-9_]+, OEN\);', '', text)
text, removed_ret = re.subn(r'\n    \(void\)hal_gpioretention_register\(DPLS_PIN_LED_[A-Z0-9_]+\);', '', text)
if removed_init != 5 or removed_ret != 5:
    raise RuntimeError(f"unexpected mode LED init/retention count: {removed_init}/{removed_ret}")
write(app, text)
replace_once(
    app,
    '''    line_window_count = line_window_pos = 0;
    vcap_window_count = vcap_window_pos = adc_decimate = 0;
    cached_line_mv = cached_vcap_mv = 0;''',
    '''    line_window_count = line_window_pos = 0;
    port2_window_count = port2_window_pos = 0;
    port_t_window_count = port_t_window_pos = 0;
    vcap_window_count = vcap_window_pos = adc_decimate = 0;
    cached_line_mv = cached_port2_mv = cached_port_t_mv = cached_vcap_mv = 0;
    adc_pending = 0u;
    adc_raw_ready = false;''',
)
replace_once(
    app,
    '''    hal.voltage_mv = voltage_mv;
    hal.port1_voltage_mv = port1_voltage_mv;
    hal.reserve_voltage_mv = reserve_voltage_mv;''',
    '''    hal.voltage_mv = voltage_mv;
    hal.port1_voltage_mv = port1_voltage_mv;
    hal.port2_voltage_mv = port2_voltage_mv;
    hal.port_t_voltage_mv = port_t_voltage_mv;
    hal.reserve_voltage_mv = reserve_voltage_mv;''',
)
replace_once(
    app,
    'adc_pending = (uint8_t)(DPLS_ADC_NEED_LINE | DPLS_ADC_NEED_VCAP);',
    'adc_pending = (uint8_t)DPLS_ADC_NEED_ALL;',
)

# Firmware version visible to the Android app and generated documentation.
replace_once(
    "Firmware/include/dpls_server.h",
    "#define DPLS_FW_VERSION_PATCH 2u",
    "#define DPLS_FW_VERSION_PATCH 3u",
)

# The generated behaviour model must not require five dev-kit mode lamps.
gen = "tools/generate_behavior_sim.py"
replace_once(
    gen,
    '''    "DPLS_PIN_LINE_ADC": "ADC · напряжение ДПЛС",
    "DPLS_PIN_VCAP_ADC": "ADC · резерв",''',
    '''    "DPLS_PIN_LINE_ADC": "ADC · +1 (legacy)",
    "DPLS_PIN_PORT1_ADC": "ADC · напряжение +1",
    "DPLS_PIN_PORT2_ADC": "ADC · напряжение +2",
    "DPLS_PIN_PORT_T_ADC": "ADC · напряжение +Т",
    "DPLS_PIN_VCAP_ADC": "ADC · резерв",''',
)
replace_once(
    gen,
    '''        elif not controls or not indicators:
            die(f"{mode_name} must expose both control and indicator writes; got {writes}")''',
    '''        elif not controls:
            die(f"{mode_name} must expose a control write; got {writes}")''',
)

# Keep package versions distinguishable even though the Android UI/protocol was
# already prepared in the previous release candidate.
replace_once("TestDPLS/app/build.gradle.kts", "versionCode = 4", "versionCode = 5")
replace_once("TestDPLS/app/build.gradle.kts", 'versionName = "1.1.1"', 'versionName = "1.1.2"')

requirements = '''# Требования к отображению напряжений в реальном времени

## Основание в ТЗ

Исходное ТЗ требует измерять напряжение ДПЛС на клеммах устройства, передавать
его через BLE и показывать в мобильном приложении. Для измерительного канала
заданы входное сопротивление не менее 1 МОм, диапазон не менее 0-30 В,
погрешность не хуже ±0,1 В в диапазоне 5-27 В и разрешение не хуже 0,1 В.

Исходное ТЗ не требует раздельных измерений на клеммах +1, +2 и +Т. Настоящий
документ вводит дополнительное требование проекта.

## Функциональные требования

1. После аутентификации одновременно отображаются напряжения +1, +2, +Т и
   резервного накопителя.
2. Значения обновляются автоматически не реже одного раза в секунду во всех
   режимах, включая «Норма».
3. По BLE передаются целые милливольты; приложение показывает вольты с двумя
   знаками после запятой.
4. Для каждого канала передаётся отдельный validity-флаг. Ноль без флага не
   считается измерением.
5. Запрещено копировать одно показание в несколько строк.
6. После трёх пропущенных периодов данные считаются устаревшими.
7. Опрос приостанавливается на время команды или выгрузки журнала и затем
   возобновляется автоматически.
8. Расширенный STATE_REPORT сохраняет 17-байтовый совместимый префикс и
   добавляет +1, +2, +Т и резерв как четыре uint16 LE.

## Аппаратная реализация revision 2

Внешний ADC или аналоговый мультиплексор не требуется. PHY6252 содержит один
ADC с внутренним мультиплексором; прошивка последовательно сканирует четыре
доступных single-ended входа:

| Измерение | GPIO | Канал SDK |
|---|---:|---:|
| +1 | P20 | ADC_CH9 |
| +2 | P15 | ADC_CH4 |
| +Т | P11 | ADC_CH0 |
| резерв | P23 | ADC_CH1 |

У каждой клеммы должен быть собственный измерительный тракт: высокоомный
делитель/защита для диапазона 0-30 В и RC-фильтр. Подавать напряжение ДПЛС
непосредственно на GPIO запрещено. Идентичные тракты +1/+2/+Т используют одну
заводскую gain/offset-калибровку; точность каждого канала проверяется отдельно.

Целевая распиновка освобождает P11 и P15 от отладочных светодиодов. KZ_1
использует P14, единственный статусный светодиод — P7. P18, P34 и P0 не
используются целевой логикой.

## Приёмка

1. В «Норме» и каждом из пяти тестовых режимов новый отчёт приходит не реже
   одного раза в секунду.
2. Все четыре validity-флага устанавливаются только после первого завершённого
   преобразования соответствующего канала.
3. Изменение каждого входа появляется в приложении не позднее двух секунд.
4. Старый 17-байтовый отчёт по-прежнему корректно читается.
5. +1, +2 и +Т проверяются поверенным вольтметром в точках 5, 12, 24 и 27 В;
   ошибка каждого канала не превышает ±0,1 В.
6. Проверяется отсутствие конфликтов GPIO: P11/P15 работают только как ADC,
   P14 только как KZ_1, P7 только как STATUS_LED.
'''
write("docs/live-voltage-requirements.md", requirements)

# Update the main firmware documentation without rewriting its history.
readme = "Firmware/README.md"
replace_once(
    readme,
    '''- измерение напряжения ДПЛС и резерва (ADC P20/P23) с усреднением по окну и
  двухточечной калибровкой gain+offset в SNV (`src/dpls_calib.c`, тест
  `tests/test_calib.c`);''',
    '''- независимое измерение +1/+2/+Т/резерва (ADC P20/P15/P11/P23) с
  последовательным сканированием, усреднением по окну и двухточечной
  калибровкой gain+offset в SNV (`src/dpls_calib.c`, тест `tests/test_calib.c`);''',
)
replace_once(
    readme,
    '''| P20 (ADC9) | DPLS_V_ADC | напряжение линии, делитель ~1/31 + буфер |
| P23 (ADC1) | VCAP_ADC | напряжение резервного ионистора |
| P24 | FACTORY_RESET | сброс пароля (только физический доступ) |
| P7 | STATUS_LED | единственный светодиод на световоде корпуса |

Соответствие режимов: «Обрыв +Т» → ISO_T; «Обрыв магистрали» → ISO_2;
«КЗ+1/+2/+Т» → KZ_1/2/T. Зарезервированы (не трогать): P11, P15, P18, P34, P0.''',
    '''| P20 (ADC9) | PORT1_ADC | напряжение +1, отдельный тракт 0-30 В |
| P15 (ADC4) | PORT2_ADC | напряжение +2, отдельный тракт 0-30 В |
| P11 (ADC0) | PORT_T_ADC | напряжение +Т, отдельный тракт 0-30 В |
| P23 (ADC1) | VCAP_ADC | напряжение резервного ионистора |
| P24 | FACTORY_RESET | сброс пароля (только физический доступ) |
| P7 | STATUS_LED | единственный светодиод на световоде корпуса |

Соответствие режимов: «Обрыв +Т» → ISO_T; «Обрыв магистрали» → ISO_2;
«КЗ+1/+2/+Т» → KZ_1/2/T. Не используются целевой логикой: P18, P34, P0.''',
)
replace_once(
    readme,
    'измерение напряжения на P20/P23.',
    'измерение напряжения на P20/P23. Каналы revision 2 на P15/P11 требуют проверки на новой плате.',
)

print("four-channel ADC release migration applied")
