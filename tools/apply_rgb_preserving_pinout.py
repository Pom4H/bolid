#!/usr/bin/env python3
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
        raise RuntimeError(f"{path}: expected one match, got {count}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))


write("Firmware/phy6252/dpls_board.h", r'''#ifndef DPLS_BOARD_H
#define DPLS_BOARD_H

#include "gpio.h"

/* Target pin assignment for Test-DPLS hardware revision 2.
 *
 * Control logic is 3.3 V, active-high. All control outputs have hardware
 * pull-downs, therefore all zeroes are the fail-safe "Norma" state.
 */
#define DPLS_PIN_ISO_1 GPIO_P31
#define DPLS_PIN_ISO_2 GPIO_P32
#define DPLS_PIN_ISO_T GPIO_P33
#define DPLS_PIN_KZ_1 GPIO_P14
#define DPLS_PIN_KZ_2 GPIO_P16
#define DPLS_PIN_KZ_T GPIO_P17

/* Four independent single-ended inputs of the PHY6252 ADC multiplexer.
 * Every external DPLS input must be connected through its own >=1 Mohm,
 * 0..30 V divider/protection path. The firmware scans one channel at a time.
 *
 * SDK channel aliases:
 *   P20 -> ADC_CH9, P15 -> ADC_CH4, P24 -> ADC_CH2, P23 -> ADC_CH1.
 */
#define DPLS_PIN_PORT1_ADC GPIO_P20
#define DPLS_PIN_PORT2_ADC GPIO_P15
#define DPLS_PIN_PORT_T_ADC GPIO_P24
#define DPLS_PIN_VCAP_ADC GPIO_P23
#define DPLS_PIN_LINE_ADC DPLS_PIN_PORT1_ADC

/* Keep the PB-03F RGB LED available. It is common-cathode / active-high:
 * red=P07, green=P11, blue=P18. The current TЗ scene uses green identify
 * flashes; red and blue remain available for explicitly specified states. */
#define DPLS_PIN_LED_RED GPIO_P07
#define DPLS_PIN_LED_GREEN GPIO_P11
#define DPLS_PIN_LED_BLUE GPIO_P18

/* Physical password reset is moved from P24 to P34 so P24 can be the fourth
 * single-ended ADC input. The target PCB must route the reset button/jumper
 * to P34. */
#define DPLS_PIN_FACTORY_RESET GPIO_P34

/* P00 is not used by the target logic. */

#endif
''')

app = "Firmware/phy6252/dpls_phy6252_app.c"
replace_once(
    app,
    '''static void status_led_output(void *context, bool on)
{
    (void)context;
    hal_gpio_write(DPLS_PIN_STATUS_LED, on ? 1 : 0);
}''',
    '''static void status_led_output(void *context, bool on)
{
    (void)context;
    /* TЗ: identify is shown by green flashes. Always drive the unused colour
     * channels low so a retained/stale RGB state cannot mix into the scene. */
    hal_gpio_write(DPLS_PIN_LED_RED, 0);
    hal_gpio_write(DPLS_PIN_LED_BLUE, 0);
    hal_gpio_write(DPLS_PIN_LED_GREEN, on ? 1 : 0);
}''',
)
replace_once(
    app,
    '''    /* One channel per conversion. P20/P15/P11 are the independent +1/+2/+T
     * voltage paths, P23 is the reserve accumulator. Standard resolution is''',
    '''    /* One channel per conversion. P20/P15/P24 are the independent +1/+2/+T
     * voltage paths, P23 is the reserve accumulator. Standard resolution is''',
)
replace_once(
    app,
    '''    } else if (adc_pending & DPLS_ADC_NEED_PORT_T) {
        channel = ADC_BIT(ADC_CH1N_P11);
        claim = DPLS_ADC_NEED_PORT_T;''',
    '''    } else if (adc_pending & DPLS_ADC_NEED_PORT_T) {
        channel = ADC_BIT(ADC_CH2N_P24);
        claim = DPLS_ADC_NEED_PORT_T;''',
)
replace_once(
    app,
    '''        case ADC_CH0:
            process_adc_channel(ch, adc_raw, size, &line_calib,''',
    '''        case ADC_CH2:
            process_adc_channel(ch, adc_raw, size, &line_calib,''',
)
text = read(app)
count = text.count("DPLS_PIN_STATUS_LED")
if count != 5:
    raise RuntimeError(f"expected 5 status LED references, got {count}")
text = text.replace("DPLS_PIN_STATUS_LED", "DPLS_PIN_LED_GREEN")
write(app, text)
replace_once(
    app,
    '''    hal_gpio_pin_init(DPLS_PIN_KZ_T, OEN);
    hal_gpio_pin_init(DPLS_PIN_LED_GREEN, OEN);''',
    '''    hal_gpio_pin_init(DPLS_PIN_KZ_T, OEN);
    hal_gpio_pin_init(DPLS_PIN_LED_RED, OEN);
    hal_gpio_pin_init(DPLS_PIN_LED_GREEN, OEN);
    hal_gpio_pin_init(DPLS_PIN_LED_BLUE, OEN);''',
)
replace_once(
    app,
    '''    (void)hal_gpioretention_register(DPLS_PIN_KZ_T);
    (void)hal_gpioretention_register(DPLS_PIN_LED_GREEN);''',
    '''    (void)hal_gpioretention_register(DPLS_PIN_KZ_T);
    (void)hal_gpioretention_register(DPLS_PIN_LED_RED);
    (void)hal_gpioretention_register(DPLS_PIN_LED_GREEN);
    (void)hal_gpioretention_register(DPLS_PIN_LED_BLUE);''',
)
replace_once(
    app,
    '''    mode_outputs_off();
    hal_gpio_write(DPLS_PIN_LED_GREEN, 0);''',
    '''    mode_outputs_off();
    hal_gpio_write(DPLS_PIN_LED_RED, 0);
    hal_gpio_write(DPLS_PIN_LED_GREEN, 0);
    hal_gpio_write(DPLS_PIN_LED_BLUE, 0);''',
)

# Generated pin map labels.
gen = "tools/generate_behavior_sim.py"
replace_once(
    gen,
    '''    "DPLS_PIN_FACTORY_RESET": "сброс пароля",
    "DPLS_PIN_STATUS_LED": "статус · идентификация",''',
    '''    "DPLS_PIN_FACTORY_RESET": "сброс пароля",
    "DPLS_PIN_LED_RED": "RGB · красный",
    "DPLS_PIN_LED_GREEN": "RGB · зелёный / идентификация",
    "DPLS_PIN_LED_BLUE": "RGB · синий",''',
)

# Requirements: preserve RGB and use P24 for +T.
req = "docs/live-voltage-requirements.md"
replace_once(req, "| +Т | P11 | ADC_CH0 |", "| +Т | P24 | ADC_CH2 |")
replace_once(
    req,
    '''Целевая распиновка освобождает P11 и P15 от отладочных светодиодов. KZ_1
использует P14, единственный статусный светодиод — P7. P18, P34 и P0 не
используются целевой логикой.''',
    '''RGB-индикация сохраняется: P7/P11/P18 — красный/зелёный/синий каналы
общего светодиода. Идентификация выполняется зелёными вспышками. +Т измеряется
на P24; физический reset переносится на P34. P15 используется только как ADC
канала +2, P14 — только как KZ_1.''',
)
replace_once(
    req,
    '''6. Проверяется отсутствие конфликтов GPIO: P11/P15 работают только как ADC,
   P14 только как KZ_1, P7 только как STATUS_LED.''',
    '''6. Проверяется отсутствие конфликтов GPIO: P20/P15/P24/P23 работают как
   +1/+2/+Т/резерв; P7/P11/P18 — только RGB; P14 — только KZ_1; P34 — reset.
7. При идентификации светодиод мигает зелёным без примеси красного и синего.''',
)

readme = "Firmware/README.md"
replace_once(
    readme,
    '''- независимое измерение +1/+2/+Т/резерва (ADC P20/P15/P11/P23) с''',
    '''- независимое измерение +1/+2/+Т/резерва (ADC P20/P15/P24/P23) с''',
)
replace_once(
    readme,
    '''| P15 (ADC4) | PORT2_ADC | напряжение +2, отдельный тракт 0-30 В |
| P11 (ADC0) | PORT_T_ADC | напряжение +Т, отдельный тракт 0-30 В |
| P23 (ADC1) | VCAP_ADC | напряжение резервного ионистора |
| P24 | FACTORY_RESET | сброс пароля (только физический доступ) |
| P7 | STATUS_LED | единственный светодиод на световоде корпуса |

Соответствие режимов: «Обрыв +Т» → ISO_T; «Обрыв магистрали» → ISO_2;
«КЗ+1/+2/+Т» → KZ_1/2/T. Не используются целевой логикой: P18, P34, P0.''',
    '''| P15 (ADC4) | PORT2_ADC | напряжение +2, отдельный тракт 0-30 В |
| P24 (ADC2) | PORT_T_ADC | напряжение +Т, отдельный тракт 0-30 В |
| P23 (ADC1) | VCAP_ADC | напряжение резервного ионистора |
| P34 | FACTORY_RESET | сброс пароля (только физический доступ) |
| P7 / P11 / P18 | RGB R / G / B | цветная индикация; identify — зелёные вспышки |

Соответствие режимов: «Обрыв +Т» → ISO_T; «Обрыв магистрали» → ISO_2;
«КЗ+1/+2/+Т» → KZ_1/2/T. P0 не используется целевой логикой.''',
)
replace_once(
    readme,
    'Каналы revision 2 на P15/P11 требуют проверки на новой плате.',
    'Каналы revision 2 на P15/P24 и RGB P7/P11/P18 требуют проверки на новой плате.',
)

print("RGB-preserving pinout applied")
