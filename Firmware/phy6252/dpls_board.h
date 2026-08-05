#ifndef DPLS_BOARD_H
#define DPLS_BOARD_H

#include "gpio.h"

/* Pin assignment for the production Test-DPLS board (АЦДР.469445.816),
 * per "Тест-ДПЛС финальная архитектура", sheet 5. Control logic is 3.3 V,
 * active-high; every control line is pulled to ground in hardware so the
 * safe default with all outputs at 0 is the "Norma" mode. At most one of the
 * mode outputs below is ever driven high at a time.
 *
 * Isolation switches (normally closed): a high level breaks the channel.
 *   ISO_1 — main port "+1"     ISO_2 — main port "+2"     ISO_T — tap "+T"
 * Short shunts (normally open): a high level shorts the port.
 *   KZ_1 — "+1"                KZ_2 — "+2"                KZ_T — "+T"
 */
#define DPLS_PIN_ISO_1 GPIO_P31
#define DPLS_PIN_ISO_2 GPIO_P32
#define DPLS_PIN_ISO_T GPIO_P33
#define DPLS_PIN_KZ_1 GPIO_P07
#define DPLS_PIN_KZ_2 GPIO_P16
#define DPLS_PIN_KZ_T GPIO_P17

/* Mode indication, one lamp per test mode, lit steadily for as long as that
 * mode is active (bench wiring for debugging; the production board carries a
 * single light guide instead). These are the PB-03F-Kit's own LEDs, so each
 * mode has a fixed colour on the bench — see docs/behavior-sim.html:
 *   P07 → LED1 red    P11 → LED1 green   P18 → LED1 blue   (one RGB5050 body,
 *                                                           common cathode)
 *   P34 → LED2 cool white              P00 → LED3 warm white
 * KZ_1 shares P07 with its own control output — the lamp is that signal, so
 * both are driven to the same level by design. */
#define DPLS_PIN_LED_OPEN_T GPIO_P00
#define DPLS_PIN_LED_OPEN_MAIN GPIO_P34
#define DPLS_PIN_LED_SHORT_1 DPLS_PIN_KZ_1
#define DPLS_PIN_LED_SHORT_2 GPIO_P11
#define DPLS_PIN_LED_SHORT_T GPIO_P18

/* Analog inputs: DPLS line voltage (divider 3.0 MΩ / 100 kΩ ≈ 1/31 through a
 * buffer op-amp) and reserve super-capacitor voltage. */
#define DPLS_PIN_LINE_ADC GPIO_P20
#define DPLS_PIN_VCAP_ADC GPIO_P23

/* Password reset — physical access only (button/jumper). On the bare
 * PB-03F-Kit there is no button on P24, so a jumper is required to exercise it. */
#define DPLS_PIN_FACTORY_RESET GPIO_P24

/* Status LED: reserve state (running from reserve / low charge) and the
 * "show on object" identify blink. Mode indication lives on its own lamps. */
#define DPLS_PIN_STATUS_LED GPIO_P14

/* Reserved on the production board — do not drive: P15. */

#endif
