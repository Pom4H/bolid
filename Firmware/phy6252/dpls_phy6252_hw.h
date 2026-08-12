#ifndef DPLS_PHY6252_HW_H
#define DPLS_PHY6252_HW_H

#include "dpls_server.h"
#include <stdbool.h>

/*
 * Owns every safety-critical digital hardware concern for the PHY6252 target:
 *   - glitch-free output initialisation;
 *   - GPIO retention through sleep;
 *   - the P16/P17 32 kHz XTAL-pad workaround;
 *   - the connection-scoped sleep lock used to keep ADC/radio clock changes
 *     from racing an active BLE link;
 *   - break-before-make mode switching and the RGB identify LED.
 *
 * The module is intentionally idempotent: the target layer calls init as early
 * as possible after hal_init(), and dpls_phy6252_app calls it again defensively.
 */
bool dpls_phy6252_hw_init(void);
bool dpls_phy6252_hw_ready(void);

void dpls_phy6252_hw_safe_normal(void);
bool dpls_phy6252_hw_apply_mode(dpls_mode_t mode);
dpls_mode_t dpls_phy6252_hw_mode(void);

void dpls_phy6252_hw_identify_led(bool on);

/* Sleep is allowed while advertising/disconnected and locked only for the
 * lifetime of an active BLE connection. Failure is fail-safe: outputs are
 * returned to Norma and false is returned to the application. */
bool dpls_phy6252_hw_connection_lock(void);
bool dpls_phy6252_hw_connection_unlock(void);

#endif
