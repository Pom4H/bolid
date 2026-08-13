#ifndef DPLS_LED_H
#define DPLS_LED_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Portable driver for the single status LED. It owns all blink timing so the
 * PHY6252 adapter only needs to forward a scene and pump the engine from a
 * timer. Timings and shapes follow "Тест-ДПЛС финальная архитектура", sheet 11:
 * the flash shape encodes the event category and the count of short flashes is
 * the channel number (1 → +1, 2 → +2, 3 → +T).
 *
 *   NORMAL        line power, healthy — LED off
 *   SHORT_1/2/T   test short on +1/+2/+T — 1/2/3 short flashes
 *   OPEN_MAIN     main-line break (channel +2) — long flash + 2 short
 *   OPEN_T        tap break (channel +T)       — long flash + 3 short
 *   AUTO_ISOLATION real short-circuit isolated (BRIZ-T) — steady on
 *   IDENTIFY      "show on object" — 1 Hz, 50 % duty, highest priority
 *
 * The reserve flag adds a double "tick" inside the inter-series pause (and, in
 * NORMAL, once every 3 s) to signal that the device runs from its reserve. */

#define DPLS_LED_SHORT_MS 150u
#define DPLS_LED_LONG_MS 800u
#define DPLS_LED_GAP_MS 250u
#define DPLS_LED_SERIES_PAUSE_MS 1600u
#define DPLS_LED_IDENTIFY_HALF_MS 500u
#define DPLS_LED_RESERVE_TICK_MS 40u
#define DPLS_LED_RESERVE_GAP_MS 120u
#define DPLS_LED_NORMAL_PERIOD_MS 3000u

typedef enum {
    DPLS_LED_SCENE_NORMAL = 0,
    DPLS_LED_SCENE_SHORT_1,
    DPLS_LED_SCENE_SHORT_2,
    DPLS_LED_SCENE_SHORT_T,
    DPLS_LED_SCENE_OPEN_MAIN,
    DPLS_LED_SCENE_OPEN_T,
    DPLS_LED_SCENE_AUTO_ISOLATION,
    DPLS_LED_SCENE_IDENTIFY
} dpls_led_scene_t;

typedef void (*dpls_led_output_fn)(void *context, bool on);

#define DPLS_LED_MAX_SEGMENTS 16u

typedef struct {
    bool on;
    uint32_t duration_ms;
} dpls_led_segment_t;

typedef struct {
    dpls_led_output_fn output;
    void *context;
    dpls_led_scene_t scene;
    bool reserve;
    bool level_set;
    bool level;
    uint32_t cycle_start_ms;
    uint32_t cycle_length_ms;
    dpls_led_segment_t segments[DPLS_LED_MAX_SEGMENTS];
    uint8_t segment_count;
} dpls_led_t;

void dpls_led_init(dpls_led_t *led, dpls_led_output_fn output, void *context, uint32_t now_ms);

/* Select the scene and reserve state. Rebuilds the timeline only when something
 * changed, so repeated identical calls from the adapter are cheap and do not
 * restart the flash sequence. */
void dpls_led_set(dpls_led_t *led, dpls_led_scene_t scene, bool reserve, uint32_t now_ms);

/* Advance the timeline, drive the output, and return the number of milliseconds
 * until the caller should tick again. Zero means the light is idle and the
 * caller must not hold a periodic timer for it: the driver owns the flash shape
 * only, so re-arming is the adapter's job once the scene changes. */
uint32_t dpls_led_tick(dpls_led_t *led, uint32_t now_ms);

#endif
