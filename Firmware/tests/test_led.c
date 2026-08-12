#include "dpls_led.h"
#include <assert.h>
#include <stdio.h>

typedef struct {
    unsigned calls;
    bool last;
} output_log_t;

static void record(void *context, bool on)
{
    output_log_t *log = context;
    ++log->calls;
    log->last = on;
}

static dpls_led_t led;
static output_log_t log;

static bool level_at(uint32_t t)
{
    (void)dpls_led_tick(&led, t);
    return led.level;
}

int main(void)
{
    log.calls = 0;
    dpls_led_init(&led, record, &log, 0u);

    /* Без идентификации LED погашен и отдельного таймера вообще нет. */
    assert(level_at(0u) == false);
    assert(dpls_led_tick(&led, 5000u) == 0u);
    assert(level_at(60000u) == false);
    assert(log.calls == 1u);

    /* Идентификация: 1 Гц, скважность 50 %, отсчёт от момента включения. */
    dpls_led_set_identify(&led, true, 1000u);
    assert(level_at(1000u) == true);
    assert(level_at(1499u) == true);
    assert(level_at(1500u) == false);
    assert(level_at(1999u) == false);
    assert(level_at(2000u) == true);
    assert(level_at(2499u) == true);
    assert(level_at(2500u) == false);

    assert(dpls_led_tick(&led, 2000u) == DPLS_LED_IDENTIFY_HALF_MS);
    assert(dpls_led_tick(&led, 2200u) == 300u);
    assert(dpls_led_tick(&led, 2500u) == DPLS_LED_IDENTIFY_HALF_MS);
    assert(dpls_led_tick(&led, 2900u) == 100u);

    (void)level_at(3000u);
    log.calls = 0u;
    dpls_led_set_identify(&led, true, 3200u);
    assert(led.cycle_start_ms == 1000u);
    assert(level_at(3200u) == true);
    assert(log.calls == 0u);

    dpls_led_set_identify(&led, false, 3300u);
    assert(level_at(3300u) == false);
    assert(dpls_led_tick(&led, 3800u) == 0u);
    assert(level_at(9000u) == false);

    dpls_led_set_identify(&led, true, 0xFFFFFF00u);
    assert(level_at(0xFFFFFF00u) == true);
    assert(level_at(0xFFFFFFFFu) == true);
    assert(level_at(0x000000F4u) == false);
    assert(level_at(0x000002E8u) == true);

    printf("test_led: all assertions passed\n");
    return 0;
}
