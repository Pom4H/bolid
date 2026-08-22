#include "dpls_phy6252_outputs.h"

#include "dpls_board.h"
#include "dpls_led.h"
#include "dpls_phy6252_power.h"
#include "gpio.h"
#include "log.h"

#define DPLS_LED_TICK_MIN_MS 10u

static dpls_led_t status_led;
static bool identify_led_active;

static void mode_outputs_off(void)
{
    hal_gpio_write(DPLS_PIN_ISO_1, 0);
    hal_gpio_write(DPLS_PIN_ISO_2, 0);
    hal_gpio_write(DPLS_PIN_ISO_T, 0);
    hal_gpio_write(DPLS_PIN_KZ_1, 0);
    hal_gpio_write(DPLS_PIN_KZ_2, 0);
    hal_gpio_write(DPLS_PIN_KZ_T, 0);
}

void dpls_phy6252_outputs_safe_normal(void *context)
{
    (void)context;
    /* GPIO sink state is unconditional. Even if pwrmgr bookkeeping is damaged,
     * a failed release can never leave a dangerous output energized. */
    mode_outputs_off();
    (void)dpls_phy6252_power_release(DPLS_POWER_OUTPUT);
}

bool dpls_phy6252_outputs_apply_mode(void *context, dpls_mode_t mode)
{
    (void)context;
    if (mode > DPLS_MODE_SHORT_T) return false;

    /* Break-before-make lives at the GPIO boundary. Logical mode is NOT copied
     * here: dpls_safety_t is the only owner of that fact. */
    mode_outputs_off();

    if (mode == DPLS_MODE_NORMAL) {
        (void)dpls_phy6252_power_release(DPLS_POWER_OUTPUT);
        return true;
    }

    /* A dangerous output may exist only while sleep is positively inhibited.
     * Acquire the constraint before energizing any pin; failure leaves NORMAL. */
    if (!dpls_phy6252_power_acquire(DPLS_POWER_OUTPUT)) return false;

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
        (void)dpls_phy6252_power_release(DPLS_POWER_OUTPUT);
        return false;
    }
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

    identify_led_active = false;
    dpls_led_init(&status_led, status_led_output, NULL, 0u);
}

void dpls_phy6252_outputs_identify(void *context, bool enabled)
{
    (void)context;
    identify_led_active = enabled;
}

uint32 dpls_phy6252_outputs_led_tick(uint32 now_ms, dpls_mode_t mode,
                                     bool reserve, bool auto_isolation)
{
    dpls_led_scene_t scene;
    uint32 delay;
    if (identify_led_active) scene = DPLS_LED_SCENE_IDENTIFY;
    else if (auto_isolation) scene = DPLS_LED_SCENE_AUTO_ISOLATION;
    else scene = scene_for_mode(mode);

    dpls_led_set(&status_led, scene, reserve, now_ms);
    delay = dpls_led_tick(&status_led, now_ms);
    if (delay == 0u) return 0u;
    /* Scene changes already wake the shared runtime scheduler. Do not cap long
     * quiet LED segments at 250 ms: that would turn a one-shot timeline back
     * into a 4 Hz polling source. */
    return delay < DPLS_LED_TICK_MIN_MS ? DPLS_LED_TICK_MIN_MS : delay;
}

bool dpls_phy6252_outputs_factory_reset_active(void)
{
    return hal_gpio_read(DPLS_PIN_FACTORY_RESET) != 0;
}

void dpls_phy6252_outputs_factory_reset_latched(void)
{
    hal_gpio_write(DPLS_PIN_LED_GREEN, 1);
}
