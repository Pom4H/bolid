#ifndef DPLS_BOARD_H
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
