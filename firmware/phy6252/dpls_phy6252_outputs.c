#include "dpls_phy6252_outputs.h"

#include "dpls_board.h"
#include "dpls_led.h"
#include "error.h"
#include "gpio.h"
#include "log.h"
#include "pwrmgr.h"

#define DPLS_LED_TICK_MIN_MS 10u
#define DPLS_LED_TICK_MAX_MS 250u

static dpls_led_t status_led;
static dpls_mode_t hardware_mode = DPLS_MODE_NORMAL;
static bool identify_led_active;
static bool control_sleep_locked;

static void mode_outputs_off(void)
{
    hal_gpio_write(DPLS_PIN_ISO_1, 0);
    hal_gpio_write(DPLS_PIN_ISO_2, 0);
    hal_gpio_write(DPLS_PIN_ISO_T, 0);
    hal_gpio_write(DPLS_PIN_KZ_1, 0);
    hal_gpio_write(DPLS_PIN_KZ_2, 0);
    hal_gpio_write(DPLS_PIN_KZ_T, 0);
}

static void control_sleep_guard(bool energized)
{
    if (energized == control_sleep_locked) return;
    if (energized) {
        if (hal_pwrmgr_lock(MOD_USR1) == PPlus_SUCCESS) control_sleep_locked = true;
    } else {
        if (hal_pwrmgr_unlock(MOD_USR1) == PPlus_SUCCESS) control_sleep_locked = false;
    }
}

void dpls_phy6252_outputs_safe_normal(void *context)
{
    (void)context;
    mode_outputs_off();
    hardware_mode = DPLS_MODE_NORMAL;
    control_sleep_guard(false);
}

bool dpls_phy6252_outputs_apply_mode(void *context, dpls_mode_t mode)
{
    (void)context;
    if (mode > DPLS_MODE_SHORT_T) return false;

    /* Break-before-make is owned here so no protocol path can energize two
     * mutually exclusive stages. */
    mode_outputs_off();
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
    hardware_mode = mode;
    control_sleep_guard(mode != DPLS_MODE_NORMAL);
    LOG("DPLS MODE %u\n", (unsigned)mode);
    return true;
}

static dpls_led_scene_t scene_for_mode(dpls_mode_t mode)
{
    switch (mode) {
    case DPLS_MODE_OPEN_T: return DPLS_LED_SCENE_OPEN_T;
    case DPLS_MODE_OPEN_MAIN: return DPLS_LED_SCENE_OPEN_MAIN;
    case DPLS_MODE_SHORT_1: return DPLS_LED_SCENE_SHORT_1;
    case DPLS_MODE_SHORT_2: return DPLS_LED_SCENE_SHORT_2;
    case DPLS_MODE_SHORT_T: return DPLS_LED_SCENE_SHORT_T;
    default: return DPLS_LED_SCENE_NORMAL;
    }
}

static void status_led_output(void *context, bool on)
{
    (void)context;
    hal_gpio_write(DPLS_PIN_LED_RED, 0);
    hal_gpio_write(DPLS_PIN_LED_BLUE, 0);
    hal_gpio_write(DPLS_PIN_LED_GREEN, on ? 1 : 0);
    LOG("DPLS LED %u\n", on ? 1u : 0u);
}

void dpls_phy6252_outputs_init(void)
{
    mode_outputs_off();
    hal_gpio_write(DPLS_PIN_LED_RED, 0);
    hal_gpio_write(DPLS_PIN_LED_GREEN, 0);
    hal_gpio_write(DPLS_PIN_LED_BLUE, 0);

    (void)hal_gpioretention_register(DPLS_PIN_ISO_1);
    (void)hal_gpioretention_register(DPLS_PIN_ISO_2);
    (void)hal_gpioretention_register(DPLS_PIN_ISO_T);
    (void)hal_gpioretention_register(DPLS_PIN_KZ_1);
    (void)hal_gpioretention_register(DPLS_PIN_KZ_2);
    (void)hal_gpioretention_register(DPLS_PIN_KZ_T);
    (void)hal_gpioretention_register(DPLS_PIN_LED_RED);
    (void)hal_gpioretention_register(DPLS_PIN_LED_GREEN);
    (void)hal_gpioretention_register(DPLS_PIN_LED_BLUE);

    hal_gpio_pin_init(DPLS_PIN_FACTORY_RESET, IE);
    hal_gpio_pull_set(DPLS_PIN_FACTORY_RESET, GPIO_PULL_DOWN);

    hardware_mode = DPLS_MODE_NORMAL;
    identify_led_active = false;
    control_sleep_locked = false;
    dpls_led_init(&status_led, status_led_output, NULL, 0u);
}

dpls_mode_t dpls_phy6252_outputs_mode(void)
{
    return hardware_mode;
}

void dpls_phy6252_outputs_identify(void *context, bool enabled)
{
    (void)context;
    identify_led_active = enabled;
}

uint32 dpls_phy6252_outputs_led_tick(uint32 now_ms, bool reserve, bool auto_isolation)
{
    dpls_led_scene_t scene;
    uint32 delay;
    if (identify_led_active) scene = DPLS_LED_SCENE_IDENTIFY;
    else if (auto_isolation) scene = DPLS_LED_SCENE_AUTO_ISOLATION;
    else scene = scene_for_mode(hardware_mode);

    dpls_led_set(&status_led, scene, reserve, now_ms);
    delay = dpls_led_tick(&status_led, now_ms);
    if (delay == 0u) return 0u;
    if (delay < DPLS_LED_TICK_MIN_MS) delay = DPLS_LED_TICK_MIN_MS;
    if (delay > DPLS_LED_TICK_MAX_MS) delay = DPLS_LED_TICK_MAX_MS;
    return delay;
}

bool dpls_phy6252_outputs_factory_reset_active(void)
{
    return hal_gpio_read(DPLS_PIN_FACTORY_RESET) != 0;
}

void dpls_phy6252_outputs_factory_reset_latched(void)
{
    hal_gpio_write(DPLS_PIN_LED_GREEN, 1);
}
