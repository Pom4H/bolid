#include "dpls_phy6252_hw.h"

#include "dpls_board.h"
#include "error.h"
#include "gpio.h"
#include "mcu.h"
#include "mcu_phy_bumbee.h"
#include "pwrmgr.h"

#include <stddef.h>

static bool initialized;
static bool ready;
static bool connection_locked;
static dpls_mode_t current_mode = DPLS_MODE_NORMAL;

/* P16/P17 are XTAL_32K_IN/OUT on PHY6252, but the Test-DPLS board uses RC32K
 * and repurposes those pads as KZ_2/KZ_T. The vendor startup/wake path still
 * biases the crystal oscillator unless PMCTL0[28] is cleared again. Register
 * this function as the wake callback of the SAME pwrmgr slot that is locked
 * while connected: one owner, one slot, no MOD_USR1/MOD_USR2 split-brain. */
static void disable_32k_xtal(void)
{
    subWriteReg(&(AP_AON->PMCTL0), 28, 28, 0x00);
}

static const gpio_pin_e retained_outputs[] = {
    DPLS_PIN_ISO_1,
    DPLS_PIN_ISO_2,
    DPLS_PIN_ISO_T,
    DPLS_PIN_KZ_1,
    DPLS_PIN_KZ_2,
    DPLS_PIN_KZ_T,
    DPLS_PIN_LED_RED,
    DPLS_PIN_LED_GREEN,
    DPLS_PIN_LED_BLUE,
};

/* hal_gpio_write() writes swporta_dr BEFORE it switches DDR to output. That is
 * the only safe initialisation primitive for an active-high output whose latch
 * may retain a previous 1 across a warm reset. Never replace this with
 * hal_gpio_pin_init(..., GPIO_OUTPUT/OEN) followed by a later write. */
static void prime_all_outputs_low(void)
{
    size_t i;
    for (i = 0; i < sizeof(retained_outputs) / sizeof(retained_outputs[0]); ++i)
        hal_gpio_write(retained_outputs[i], 0);
}

static bool register_output_retention(void)
{
    size_t i;
    for (i = 0; i < sizeof(retained_outputs) / sizeof(retained_outputs[0]); ++i) {
        if (hal_gpioretention_register(retained_outputs[i]) != PPlus_SUCCESS)
            return false;
    }
    return true;
}

void dpls_phy6252_hw_safe_normal(void)
{
    hal_gpio_write(DPLS_PIN_ISO_1, 0);
    hal_gpio_write(DPLS_PIN_ISO_2, 0);
    hal_gpio_write(DPLS_PIN_ISO_T, 0);
    hal_gpio_write(DPLS_PIN_KZ_1, 0);
    hal_gpio_write(DPLS_PIN_KZ_2, 0);
    hal_gpio_write(DPLS_PIN_KZ_T, 0);
    current_mode = DPLS_MODE_NORMAL;
}

bool dpls_phy6252_hw_init(void)
{
    int rc;

    if (initialized)
        return ready;
    initialized = true;

    /* The order is deliberate: remove the XTAL bias, preload every latch low,
     * then register GPIO retention. hal_gpioretention_register() itself changes
     * the direction to output, so doing it before the low write would recreate
     * the startup pulse fixed by this module. */
    disable_32k_xtal();
    prime_all_outputs_low();

    if (!register_output_retention()) {
        dpls_phy6252_hw_safe_normal();
        ready = false;
        return false;
    }

    /* MOD_USR1 is owned exclusively by this module. Its wake callback restores
     * the RC32K/P16/P17 condition after every low-power wake. The same module is
     * locked while BLE is connected, which eliminates the ADC/radio/sleep race
     * without consuming a second pwrmgr registration slot. */
    rc = hal_pwrmgr_register(MOD_USR1, NULL, disable_32k_xtal);
    if (rc != PPlus_SUCCESS) {
        dpls_phy6252_hw_safe_normal();
        ready = false;
        return false;
    }

    connection_locked = false;
    dpls_phy6252_hw_safe_normal();
    dpls_phy6252_hw_identify_led(false);
    ready = true;
    return true;
}

bool dpls_phy6252_hw_ready(void)
{
    return initialized && ready;
}

bool dpls_phy6252_hw_apply_mode(dpls_mode_t mode)
{
    if (!dpls_phy6252_hw_ready() || mode > DPLS_MODE_SHORT_T) {
        dpls_phy6252_hw_safe_normal();
        return false;
    }

    /* Break-before-make is centralized here so no protocol path can accidentally
     * assert two power-stage controls at once. */
    dpls_phy6252_hw_safe_normal();
    switch (mode) {
    case DPLS_MODE_NORMAL:
        break;
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
    default:
        return false;
    }
    current_mode = mode;
    return true;
}

dpls_mode_t dpls_phy6252_hw_mode(void)
{
    return current_mode;
}

void dpls_phy6252_hw_identify_led(bool on)
{
    /* Common-cathode RGB LED, active-high. Unused colours are forced low on
     * every update so a retained value can never produce a mixed colour. */
    hal_gpio_write(DPLS_PIN_LED_RED, 0);
    hal_gpio_write(DPLS_PIN_LED_BLUE, 0);
    hal_gpio_write(DPLS_PIN_LED_GREEN, on ? 1 : 0);
}

bool dpls_phy6252_hw_connection_lock(void)
{
    if (!dpls_phy6252_hw_ready()) {
        dpls_phy6252_hw_safe_normal();
        return false;
    }
    if (connection_locked)
        return true;

    if (hal_pwrmgr_lock(MOD_USR1) != PPlus_SUCCESS) {
        ready = false;
        dpls_phy6252_hw_safe_normal();
        return false;
    }
    connection_locked = true;
    return true;
}

bool dpls_phy6252_hw_connection_unlock(void)
{
    if (!connection_locked)
        return true;

    if (hal_pwrmgr_unlock(MOD_USR1) != PPlus_SUCCESS) {
        connection_locked = false;
        ready = false;
        dpls_phy6252_hw_safe_normal();
        return false;
    }
    connection_locked = false;
    return true;
}
