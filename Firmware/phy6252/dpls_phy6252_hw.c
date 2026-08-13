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
static bool control_sleep_locked;
static dpls_mode_t current_mode = DPLS_MODE_NORMAL;

/* P16/P17 are XTAL_32K_IN/OUT on PHY6252, but the Test-DPLS board uses RC32K
 * and repurposes those pads as KZ_2/KZ_T. Restore that mux condition after each
 * low-power wake. GPIO retention keeps their output level while sleeping. */
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
 * the safe initialisation primitive for an active-high output whose latch may
 * retain a previous 1 across a warm reset. */
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

static void drive_controls_low(void)
{
    hal_gpio_write(DPLS_PIN_ISO_1, 0);
    hal_gpio_write(DPLS_PIN_ISO_2, 0);
    hal_gpio_write(DPLS_PIN_ISO_T, 0);
    hal_gpio_write(DPLS_PIN_KZ_1, 0);
    hal_gpio_write(DPLS_PIN_KZ_2, 0);
    hal_gpio_write(DPLS_PIN_KZ_T, 0);
    current_mode = DPLS_MODE_NORMAL;
}

static bool control_sleep_guard_acquire(void)
{
    if (control_sleep_locked)
        return true;
    if (hal_pwrmgr_lock(MOD_USR1) != PPlus_SUCCESS)
        return false;
    control_sleep_locked = true;
    return true;
}

static bool control_sleep_guard_release(void)
{
    if (!control_sleep_locked)
        return true;
    if (hal_pwrmgr_unlock(MOD_USR1) != PPlus_SUCCESS) {
        control_sleep_locked = false;
        ready = false;
        return false;
    }
    control_sleep_locked = false;
    return true;
}

void dpls_phy6252_hw_safe_normal(void)
{
    /* Drop every active-high control before allowing sleep again. This ordering
     * means a failed/unexpected transition always leaves the power stage passive. */
    drive_controls_low();
    if (!control_sleep_guard_release())
        ready = false;
}

bool dpls_phy6252_hw_init(void)
{
    int rc;

    if (initialized)
        return ready;
    initialized = true;

    disable_32k_xtal();
    prime_all_outputs_low();

    if (!register_output_retention()) {
        drive_controls_low();
        ready = false;
        return false;
    }

    /* One user pwrmgr slot owns both policies: wake restores the RC32K/P16/P17
     * mux, and the slot is locked only while a non-normal power-stage output is
     * asserted. Ordinary BLE and ADC sampling can therefore use system sleep;
     * the ADC driver independently owns MOD_ADCC during each conversion. */
    rc = hal_pwrmgr_register(MOD_USR1, NULL, disable_32k_xtal);
    if (rc != PPlus_SUCCESS) {
        drive_controls_low();
        ready = false;
        return false;
    }

    control_sleep_locked = false;
    drive_controls_low();
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

    if (mode == DPLS_MODE_NORMAL) {
        dpls_phy6252_hw_safe_normal();
        return dpls_phy6252_hw_ready();
    }

    /* Safety beats power while a physical fault/open simulation is active. Keep
     * the core awake for this bounded test window, then safe_normal() releases
     * the guard. Normal connected operation remains low-power. */
    if (!control_sleep_guard_acquire()) {
        ready = false;
        drive_controls_low();
        return false;
    }

    /* Break-before-make is centralized here so no protocol path can accidentally
     * assert two power-stage controls at once. Do not call safe_normal() here:
     * that would also release the guard we just acquired. */
    drive_controls_low();
    switch (mode) {
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
    case DPLS_MODE_NORMAL:
    default:
        dpls_phy6252_hw_safe_normal();
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
    hal_gpio_write(DPLS_PIN_LED_RED, 0);
    hal_gpio_write(DPLS_PIN_LED_BLUE, 0);
    hal_gpio_write(DPLS_PIN_LED_GREEN, on ? 1 : 0);
}

bool dpls_phy6252_hw_connection_lock(void)
{
    /* The connection itself may sleep. ADC work is synchronized to the quiet
     * interval after each radio event, while MOD_USR1 remains reserved for an
     * asserted power-stage output. */
    if (!dpls_phy6252_hw_ready()) {
        dpls_phy6252_hw_safe_normal();
        return false;
    }
    disable_32k_xtal();
    return true;
}

bool dpls_phy6252_hw_connection_unlock(void)
{
    /* Disconnect is another fail-safe boundary: outputs go passive first and
     * any active-mode sleep guard is released afterwards. */
    dpls_phy6252_hw_safe_normal();
    return dpls_phy6252_hw_ready();
}
