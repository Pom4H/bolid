#ifndef DPLS_ADC_MATH_H
#define DPLS_ADC_MATH_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* PHY62XX efuse calibration is considered usable only inside the same bounds
 * enforced by vendor SDK 3.1.2 hal_adc_load_calibration_value(). */
bool dpls_adc_hw_calibration_valid(uint16_t negative, uint16_t positive);

/* Fixed-point equivalent of PHY62XX SDK 3.1.2 hal_adc_value_cal() for the only
 * mode used by Test-DPLS: single-ended, low-resolution ADC.
 *
 * lambda is the per-channel vendor adc_Lambda[] coefficient. The result is
 * rounded to the nearest millivolt and saturated to uint16_t. Keeping this
 * conversion integer-only avoids pulling software floating-point into the
 * Cortex-M0 image for four conversions every second. */
uint16_t dpls_adc_single_ended_mv(uint8_t channel,
                                  const uint16_t *samples,
                                  uint8_t sample_count,
                                  uint16_t calibration_negative,
                                  uint16_t calibration_positive,
                                  uint32_t lambda);

#endif
