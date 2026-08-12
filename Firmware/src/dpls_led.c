#include "dpls_led.h"

#define DPLS_LED_PERIOD_MS (2u * DPLS_LED_IDENTIFY_HALF_MS)

static void apply_level(dpls_led_t *led, bool on)
{
    if (led->level_set && led->level == on) return;
    led->level_set = true;
    led->level = on;
    if (led->output) led->output(led->context, on);
}

void dpls_led_init(dpls_led_t *led, dpls_led_output_fn output, void *context, uint32_t now_ms)
{
    led->output = output;
    led->context = context;
    led->identify = false;
    led->level_set = false;
    led->level = false;
    led->cycle_start_ms = now_ms;
}

void dpls_led_set_identify(dpls_led_t *led, bool identify, uint32_t now_ms)
{
    if (led->identify == identify) return;
    led->identify = identify;
    led->cycle_start_ms = now_ms;
    led->level_set = false;
}

uint32_t dpls_led_tick(dpls_led_t *led, uint32_t now_ms)
{
    uint32_t phase;

    if (!led->identify) {
        apply_level(led, false);
        /* Zero means there is no future LED work. The target re-arms this state
         * machine when an IDENTIFY command changes the flag. */
        return 0u;
    }
    phase = (now_ms - led->cycle_start_ms) % DPLS_LED_PERIOD_MS;
    if (phase < DPLS_LED_IDENTIFY_HALF_MS) {
        apply_level(led, true);
        return DPLS_LED_IDENTIFY_HALF_MS - phase;
    }
    apply_level(led, false);
    return DPLS_LED_PERIOD_MS - phase;
}
