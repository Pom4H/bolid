#ifndef DPLS_BOARD_H
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
 */
#define DPLS_PIN_PORT1_ADC GPIO_P20
#define DPLS_PIN_PORT2_ADC GPIO_P15
#define DPLS_PIN_PORT_T_ADC GPIO_P24
#define DPLS_PIN_VCAP_ADC GPIO_P23
#define DPLS_PIN_LINE_ADC DPLS_PIN_PORT1_ADC

/* Multiplexer channel behind each ADC-capable pin. Only routed pins are listed,
 * so DPLS_ADC_CHANNEL() of anything else fails to compile. The bodies name
 * <adc.h> enumerators but expand at the point of use, which keeps this header
 * dependent on <gpio.h> alone. */
#define DPLS_ADC_CHANNEL_GPIO_P20 ADC_CH3P_P20
#define DPLS_ADC_CHANNEL_GPIO_P15 ADC_CH3N_P15
#define DPLS_ADC_CHANNEL_GPIO_P24 ADC_CH2N_P24
#define DPLS_ADC_CHANNEL_GPIO_P23 ADC_CH1P_P23
#define DPLS_ADC_CHANNEL_OF(pin) DPLS_ADC_CHANNEL_##pin
#define DPLS_ADC_CHANNEL(pin) DPLS_ADC_CHANNEL_OF(pin)

/* Common-cathode RGB, active-high. Scenes use green; red and blue are free for
 * states the TЗ may specify later. */
#define DPLS_PIN_LED_RED GPIO_P07
#define DPLS_PIN_LED_GREEN GPIO_P11
#define DPLS_PIN_LED_BLUE GPIO_P18

/* P24 is the fourth ADC input, so the reset button/jumper belongs on P34. */
#define DPLS_PIN_FACTORY_RESET GPIO_P34

/* P00 is not used by the target logic. */

#endif
