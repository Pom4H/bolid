#include "dpls_adc_math.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>

static uint32_t rng_state = 0x6252d1a5u;

static uint32_t next_random(void)
{
    rng_state = rng_state * 1664525u + 1013904223u;
    return rng_state;
}

static uint16_t reference_mv(uint8_t channel,
                             const uint16_t *samples,
                             uint8_t sample_count,
                             uint16_t negative,
                             uint16_t positive,
                             uint32_t lambda)
{
    uint32_t sum = 0u;
    uint8_t i;
    double result;
    uint32_t mv;

    for (i = 0u; i < sample_count; ++i)
        sum += samples[i] & 0x0fffu;
    result = (double)sum / (double)sample_count;

    if (dpls_adc_hw_calibration_valid(negative, positive)) {
        double delta = ((int32_t)positive - (int32_t)negative) / 2.0;
        result = (channel & 1u)
            ? (result - delta) / ((double)positive + (double)negative)
            : (result + delta) / ((double)positive + (double)negative);
    } else {
        result /= 4096.0;
    }

    result = result * (double)lambda * 0.8 / 1000000.0;
    if (result <= 0.0)
        return 0u;
    mv = (uint32_t)(result * 1000.0 + 0.5);
    return mv > 65535u ? 65535u : (uint16_t)mv;
}

static void check_case(uint8_t channel, uint32_t lambda, bool calibrated)
{
    uint16_t samples[32];
    unsigned iteration;

    for (iteration = 0u; iteration < 5000u; ++iteration) {
        uint8_t count = (uint8_t)(1u + next_random() % 32u);
        uint16_t negative;
        uint16_t positive;
        uint8_t i;
        uint16_t expected;
        uint16_t actual;
        int32_t difference;

        for (i = 0u; i < count; ++i)
            samples[i] = (uint16_t)(next_random() & 0x0fffu);

        if (calibrated) {
            negative = (uint16_t)(0x733u + next_random() % (0x8ccu - 0x733u + 1u));
            positive = (uint16_t)(0x733u + next_random() % (0x8ccu - 0x733u + 1u));
        } else {
            negative = 0x0fffu;
            positive = 0x0fffu;
        }

        expected = reference_mv(channel, samples, count, negative, positive, lambda);
        actual = dpls_adc_single_ended_mv(channel, samples, count, negative, positive, lambda);
        difference = (int32_t)actual - (int32_t)expected;
        assert(difference >= -1 && difference <= 1);
    }
}

int main(void)
{
    uint16_t zero = 0u;

    assert(dpls_adc_hw_calibration_valid(0x733u, 0x8ccu));
    assert(!dpls_adc_hw_calibration_valid(0x732u, 0x800u));
    assert(!dpls_adc_hw_calibration_valid(0x800u, 0x8cdu));
    assert(dpls_adc_single_ended_mv(7u, NULL, 1u, 0x800u, 0x800u, 4072069u) == 0u);
    assert(dpls_adc_single_ended_mv(7u, &zero, 0u, 0x800u, 0x800u, 4072069u) == 0u);
    assert(dpls_adc_single_ended_mv(7u, &zero, 1u, 0x800u, 0x800u, 0u) == 0u);

    /* QFN32 coefficients from pinned PHY62XX SDK 3.1.2 for the four product
     * inputs: P20/CH9, P15/CH4, P24/CH2, P23/CH1. */
    check_case(7u, 4072069u, true);
    check_case(6u, 4180401u, true);
    check_case(4u, 4263287u, true);
    check_case(3u, 4308639u, true);
    check_case(7u, 4072069u, false);
    check_case(6u, 4180401u, false);
    check_case(4u, 4263287u, false);
    check_case(3u, 4308639u, false);

    puts("test_adc_math: OK");
    return 0;
}
