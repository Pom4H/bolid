#include "dpls_led.h"

static void seg_add(dpls_led_t *led, bool on, uint32_t ms)
{
    if (ms == 0u || led->segment_count >= DPLS_LED_MAX_SEGMENTS) return;
    /* Coalesce adjacent same-level runs so the reserve injection below cannot
     * overflow the segment buffer and the timeline stays minimal. */
    if (led->segment_count > 0u && led->segments[led->segment_count - 1u].on == on) {
        led->segments[led->segment_count - 1u].duration_ms += ms;
        return;
    }
    led->segments[led->segment_count].on = on;
    led->segments[led->segment_count].duration_ms = ms;
    ++led->segment_count;
}

static void seg_add_series_pause(dpls_led_t *led)
{
    uint32_t lead = 200u;
    uint32_t tail;
    if (!led->reserve) {
        seg_add(led, false, DPLS_LED_SERIES_PAUSE_MS);
        return;
    }
    /* Double "tick" at the head of the inter-series pause: run from reserve. */
    tail = DPLS_LED_SERIES_PAUSE_MS - lead - 2u * DPLS_LED_RESERVE_TICK_MS - DPLS_LED_RESERVE_GAP_MS;
    seg_add(led, false, lead);
    seg_add(led, true, DPLS_LED_RESERVE_TICK_MS);
    seg_add(led, false, DPLS_LED_RESERVE_GAP_MS);
    seg_add(led, true, DPLS_LED_RESERVE_TICK_MS);
    seg_add(led, false, tail);
}

static void build_timeline(dpls_led_t *led)
{
    uint8_t shorts = 0u, pulses, index = 0u, i;
    bool has_long = false;

    led->segment_count = 0u;

    switch (led->scene) {
    case DPLS_LED_SCENE_NORMAL:
        if (!led->reserve) {
            /* Nothing to show: leave the timeline empty so dpls_led_tick() can
             * report "no work" and the adapter drops its periodic timer. A
             * single all-off segment would keep waking the core forever. */
        } else {
            seg_add(led, true, DPLS_LED_RESERVE_TICK_MS);
            seg_add(led, false, DPLS_LED_RESERVE_GAP_MS);
            seg_add(led, true, DPLS_LED_RESERVE_TICK_MS);
            seg_add(led, false, DPLS_LED_NORMAL_PERIOD_MS -
                                    2u * DPLS_LED_RESERVE_TICK_MS - DPLS_LED_RESERVE_GAP_MS);
        }
        break;
    case DPLS_LED_SCENE_AUTO_ISOLATION:
        seg_add(led, true, DPLS_LED_NORMAL_PERIOD_MS);
        break;
    case DPLS_LED_SCENE_IDENTIFY:
        seg_add(led, true, DPLS_LED_IDENTIFY_HALF_MS);
        seg_add(led, false, DPLS_LED_IDENTIFY_HALF_MS);
        break;
    case DPLS_LED_SCENE_SHORT_1: shorts = 1u; break;
    case DPLS_LED_SCENE_SHORT_2: shorts = 2u; break;
    case DPLS_LED_SCENE_SHORT_T: shorts = 3u; break;
    case DPLS_LED_SCENE_OPEN_MAIN: has_long = true; shorts = 2u; break;
    case DPLS_LED_SCENE_OPEN_T: has_long = true; shorts = 3u; break;
    default: break; /* unknown scene: stay dark and idle */
    }

    if (shorts != 0u || has_long) {
        pulses = (uint8_t)((has_long ? 1u : 0u) + shorts);
        if (has_long) {
            seg_add(led, true, DPLS_LED_LONG_MS);
            if (++index < pulses) seg_add(led, false, DPLS_LED_GAP_MS);
        }
        for (i = 0u; i < shorts; ++i) {
            seg_add(led, true, DPLS_LED_SHORT_MS);
            if (++index < pulses) seg_add(led, false, DPLS_LED_GAP_MS);
        }
        seg_add_series_pause(led);
    }

    led->cycle_length_ms = 0u;
    for (i = 0u; i < led->segment_count; ++i) led->cycle_length_ms += led->segments[i].duration_ms;
}

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
    led->scene = DPLS_LED_SCENE_NORMAL;
    led->reserve = false;
    led->level_set = false;
    led->level = false;
    led->cycle_start_ms = now_ms;
    build_timeline(led);
}

void dpls_led_set(dpls_led_t *led, dpls_led_scene_t scene, bool reserve, uint32_t now_ms)
{
    if (led->scene == scene && led->reserve == reserve) return;
    led->scene = scene;
    led->reserve = reserve;
    led->cycle_start_ms = now_ms;
    led->level_set = false;
    build_timeline(led);
}

uint32_t dpls_led_tick(dpls_led_t *led, uint32_t now_ms)
{
    uint32_t elapsed, accumulated = 0u;
    uint8_t i;

    if (led->cycle_length_ms == 0u || led->segment_count == 0u) {
        apply_level(led, false);
        /* Zero means there is no future LED work, so the adapter must not keep a
         * periodic timer running. It re-arms the scheduler when a command or a
         * measurement changes the scene. */
        return 0u;
    }

    elapsed = now_ms - led->cycle_start_ms;
    if (elapsed >= led->cycle_length_ms) {
        uint32_t cycles = elapsed / led->cycle_length_ms;
        led->cycle_start_ms += cycles * led->cycle_length_ms;
        elapsed -= cycles * led->cycle_length_ms;
    }

    for (i = 0u; i < led->segment_count; ++i) {
        if (elapsed < accumulated + led->segments[i].duration_ms) {
            uint32_t remaining = accumulated + led->segments[i].duration_ms - elapsed;
            apply_level(led, led->segments[i].on);
            return remaining == 0u ? 1u : remaining;
        }
        accumulated += led->segments[i].duration_ms;
    }

    apply_level(led, led->segments[led->segment_count - 1u].on);
    return 1u;
}
