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
