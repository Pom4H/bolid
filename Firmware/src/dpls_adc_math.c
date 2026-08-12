#include "dpls_adc_math.h"

#include <limits.h>

#define DPLS_ADC_EFUSE_MIN 0x733u
#define DPLS_ADC_EFUSE_MAX 0x8ccu
#define DPLS_ADC_RAW_FULL_SCALE 4096u
/* Vendor low-resolution formula multiplies normalized ADC by
 * lambda * 0.8 / 1,000,000 volts. Converting that to millivolts gives
 * normalized * lambda / 1250 exactly. */
#define DPLS_ADC_LAMBDA_MV_DIVISOR 1250u

bool dpls_adc_hw_calibration_valid(uint16_t negative, uint16_t positive)
{
    return negative >= DPLS_ADC_EFUSE_MIN && negative <= DPLS_ADC_EFUSE_MAX &&
           positive >= DPLS_ADC_EFUSE_MIN && positive <= DPLS_ADC_EFUSE_MAX;
}

uint16_t dpls_adc_single_ended_mv(uint8_t channel,
                                  const uint16_t *samples,
                                  uint8_t sample_count,
                                  uint16_t calibration_negative,
                                  uint16_t calibration_positive,
                                  uint32_t lambda)
{
    uint32_t sum = 0u;
    uint32_t denominator;
    uint64_t scaled;
    int32_t numerator;
    uint8_t i;

    if (samples == NULL || sample_count == 0u || lambda == 0u)
        return 0u;

    for (i = 0u; i < sample_count; ++i)
        sum += (uint32_t)(samples[i] & 0x0fffu);

    if (dpls_adc_hw_calibration_valid(calibration_negative, calibration_positive)) {
        int32_t difference = (int32_t)calibration_positive - (int32_t)calibration_negative;
        /*
         * Vendor SDK:
         *   average = sum / N
         *   delta   = (positive - negative) / 2
         *   odd ch  = (average - delta) / (positive + negative)
         *   even ch = (average + delta) / (positive + negative)
         *
         * Multiply numerator/denominator by 2*N so no precision is lost before
         * the final rounded mV conversion. Bounds for N<=32 remain well inside
         * int32/uint32; only numerator*lambda needs uint64_t.
         */
        numerator = (int32_t)(2u * sum);
        if (channel & 0x01u)
            numerator -= (int32_t)sample_count * difference;
        else
            numerator += (int32_t)sample_count * difference;
        denominator = 2u * (uint32_t)sample_count *
                      ((uint32_t)calibration_positive + (uint32_t)calibration_negative) *
                      DPLS_ADC_LAMBDA_MV_DIVISOR;
    } else {
        /* Same fallback as vendor SDK when efuse values are out of range. */
        numerator = (int32_t)sum;
        denominator = (uint32_t)sample_count * DPLS_ADC_RAW_FULL_SCALE *
                      DPLS_ADC_LAMBDA_MV_DIVISOR;
    }

    if (numerator <= 0 || denominator == 0u)
        return 0u;

    scaled = (uint64_t)(uint32_t)numerator * (uint64_t)lambda;
    scaled = (scaled + (uint64_t)(denominator / 2u)) / (uint64_t)denominator;
    return scaled > UINT16_MAX ? UINT16_MAX : (uint16_t)scaled;
}
