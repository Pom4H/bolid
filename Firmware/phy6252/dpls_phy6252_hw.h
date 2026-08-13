#ifndef DPLS_PHY6252_HW_H
#define DPLS_PHY6252_HW_H

#include "dpls_server.h"
#include <stdbool.h>

/*
 * Owns every safety-critical digital hardware concern for the PHY6252 target:
 *   - glitch-free output initialisation;
 *   - GPIO retention through sleep;
 *   - the P16/P17 32 kHz XTAL-pad workaround;
 *   - a safety sleep guard while any active-high power-stage mode is asserted;
 *   - break-before-make mode switching and the RGB identify LED.
 *
 * Normal BLE operation is allowed to sleep. ADC conversions are synchronized
 * to quiet post-radio windows and own MOD_ADCC only for the short conversion;
 * MOD_USR1 stays reserved for an asserted power-stage output.
 *
 * The module is idempotent: the target layer calls init as early as possible
 * and dpls_phy6252_app calls it again defensively.
 */
bool dpls_phy6252_hw_init(void);
bool dpls_phy6252_hw_ready(void);

void dpls_phy6252_hw_safe_normal(void);
bool dpls_phy6252_hw_apply_mode(dpls_mode_t mode);
dpls_mode_t dpls_phy6252_hw_mode(void);

void dpls_phy6252_hw_identify_led(bool on);

/* Historical API names retained to avoid coupling the app to the power-policy
 * implementation. Connection start/end validate/reset hardware state. */
bool dpls_phy6252_hw_connection_lock(void);
bool dpls_phy6252_hw_connection_unlock(void);

#endif
