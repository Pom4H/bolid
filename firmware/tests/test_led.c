#include "dpls_led.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    unsigned calls;
    bool last;
} output_log_t;

static void record(void *c, bool on)
{
    output_log_t *log = c;
    ++log->calls;
    log->last = on;
}

static dpls_led_t led;
static output_log_t log;

static void expect_segments(dpls_led_scene_t scene, bool reserve,
                            const dpls_led_segment_t *want, uint8_t want_count,
                            const char *label)
{
    uint8_t i;
    uint32_t total = 0u;
    dpls_led_set(&led, scene, reserve, 0u);
    if (led.segment_count != want_count) {
        printf("FAIL %s: segment_count=%u want=%u\n", label, led.segment_count, want_count);
        assert(0);
    }
    for (i = 0u; i < want_count; ++i) {
        if (led.segments[i].on != want[i].on || led.segments[i].duration_ms != want[i].duration_ms) {
            printf("FAIL %s: seg[%u]={%d,%u} want={%d,%u}\n", label, i,
                   led.segments[i].on, (unsigned)led.segments[i].duration_ms,
                   want[i].on, (unsigned)want[i].duration_ms);
            assert(0);
        }
        total += want[i].duration_ms;
    }
    assert(led.cycle_length_ms == total);
}

/* Advance to an absolute time (samples must be monotonically increasing). */
static bool level_at(uint32_t t)
{
    dpls_led_tick(&led, t);
    return led.level;
}

static unsigned count_flashes(dpls_led_scene_t scene, bool reserve)
{
    uint32_t t, step = 5u, cycle;
    bool prev;
    unsigned edges = 0u;
    dpls_led_set(&led, scene, reserve, 0u);
    cycle = led.cycle_length_ms;
    prev = level_at(0u);
    if (prev) ++edges; /* a cycle that starts lit begins with a flash */
    for (t = step; t < cycle; t += step) {
        bool now = level_at(t);
        if (now && !prev) ++edges;
        prev = now;
    }
    return edges;
}

/* Norma without reserve must cost no wake-ups: an empty timeline, a tick that
 * reports "no work", and an output driven off. */
static void expect_dormant_in_norma(void)
{
    dpls_led_set(&led, DPLS_LED_SCENE_NORMAL, false, 0u);
    assert(led.segment_count == 0u);
    assert(led.cycle_length_ms == 0u);
    assert(dpls_led_tick(&led, 0u) == 0u);
    assert(!led.level);
    assert(dpls_led_tick(&led, 10000u) == 0u);
}

int main(void)
{
    static const dpls_led_segment_t normal_res[] = {
        {true, 40u}, {false, 120u}, {true, 40u}, {false, 2800u}};
    static const dpls_led_segment_t short1[] = {{true, 150u}, {false, 1600u}};
    static const dpls_led_segment_t short2[] = {
        {true, 150u}, {false, 250u}, {true, 150u}, {false, 1600u}};
    static const dpls_led_segment_t shortt[] = {
        {true, 150u}, {false, 250u}, {true, 150u}, {false, 250u}, {true, 150u}, {false, 1600u}};
    static const dpls_led_segment_t open_main[] = {
        {true, 800u}, {false, 250u}, {true, 150u}, {false, 250u}, {true, 150u}, {false, 1600u}};
    static const dpls_led_segment_t open_t[] = {
        {true, 800u}, {false, 250u}, {true, 150u}, {false, 250u},
        {true, 150u}, {false, 250u}, {true, 150u}, {false, 1600u}};
    static const dpls_led_segment_t open_t_res[] = {
        {true, 800u}, {false, 250u}, {true, 150u}, {false, 250u},
        {true, 150u}, {false, 250u}, {true, 150u},
        {false, 200u}, {true, 40u}, {false, 120u}, {true, 40u}, {false, 1200u}};
    static const dpls_led_segment_t isolation[] = {{true, 3000u}};
    static const dpls_led_segment_t identify[] = {{true, 500u}, {false, 500u}};

    dpls_led_init(&led, record, &log, 0u);

    expect_dormant_in_norma();
    expect_segments(DPLS_LED_SCENE_NORMAL, true, normal_res, 4u, "normal+reserve");
    expect_segments(DPLS_LED_SCENE_SHORT_1, false, short1, 2u, "short1");
    expect_segments(DPLS_LED_SCENE_SHORT_2, false, short2, 4u, "short2");
    expect_segments(DPLS_LED_SCENE_SHORT_T, false, shortt, 6u, "shortT");
    expect_segments(DPLS_LED_SCENE_OPEN_MAIN, false, open_main, 6u, "open_main");
    expect_segments(DPLS_LED_SCENE_OPEN_T, false, open_t, 8u, "open_T");
    expect_segments(DPLS_LED_SCENE_OPEN_T, true, open_t_res, 12u, "open_T+reserve");
    expect_segments(DPLS_LED_SCENE_AUTO_ISOLATION, false, isolation, 1u, "isolation");
    expect_segments(DPLS_LED_SCENE_IDENTIFY, false, identify, 2u, "identify");

    /* Channel number == count of short flashes; breaks add one long lead flash. */
    assert(count_flashes(DPLS_LED_SCENE_SHORT_1, false) == 1u);
    assert(count_flashes(DPLS_LED_SCENE_SHORT_2, false) == 2u);
    assert(count_flashes(DPLS_LED_SCENE_SHORT_T, false) == 3u);
    assert(count_flashes(DPLS_LED_SCENE_OPEN_MAIN, false) == 3u);
    assert(count_flashes(DPLS_LED_SCENE_OPEN_T, false) == 4u);

    /* Timeline playback + wrap for the simplest pulsed scene. */
    dpls_led_set(&led, DPLS_LED_SCENE_SHORT_1, false, 0u);
    assert(level_at(0u) == true);
    assert(level_at(149u) == true);
    assert(level_at(150u) == false);
    assert(level_at(1749u) == false);
    assert(level_at(1750u) == true);  /* second cycle begins */
    assert(level_at(1899u) == true);
    assert(level_at(1900u) == false);

    /* Steady scenes never toggle. */
    dpls_led_set(&led, DPLS_LED_SCENE_AUTO_ISOLATION, false, 0u);
    assert(level_at(0u) == true);
    assert(level_at(5000u) == true);
    assert(level_at(9000u) == true);

    /* Re-selecting the same scene must not restart the sequence or re-drive. */
    dpls_led_set(&led, DPLS_LED_SCENE_SHORT_2, false, 0u);
    (void)dpls_led_tick(&led, 0u);
    log.calls = 0u;
    dpls_led_set(&led, DPLS_LED_SCENE_SHORT_2, false, 100u);
    assert(led.cycle_start_ms == 0u);
    (void)dpls_led_tick(&led, 100u);
    assert(log.calls == 0u); /* still within the first ON segment, no edge */

    printf("test_led: all assertions passed\n");
    return 0;
}
