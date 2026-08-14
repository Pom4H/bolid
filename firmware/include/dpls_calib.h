#ifndef DPLS_CALIB_H
#define DPLS_CALIB_H

#include <stdbool.h>
#include <stdint.h>

/* Linear calibration from ADC pin voltage to DPLS line voltage, both in mV.
 * The hardware divider is nominally 1/31 (3.0 MΩ / 100 kΩ), so the default
 * gain is 31.000. Two-point factory calibration replaces the nominal gain and
 * offset to reach the ТЗ 4.3.7 accuracy of ±0.1 V across 5…27 V. */

#define DPLS_CALIB_NOMINAL_GAIN_MILLI 31000u
#define DPLS_CALIB_MAX_MV 30000u          /* ТЗ 4.3.7 range 0…30 V */
#define DPLS_CALIB_GAIN_MIN_MILLI 20000u  /* sanity bounds for stored values */
#define DPLS_CALIB_GAIN_MAX_MILLI 45000u

typedef struct {
    uint32_t gain_milli;  /* line_mv per pin_mv, scaled by 1000 */
    int32_t offset_mv;    /* additive correction after gain */
} dpls_calib_t;

void dpls_calib_default(dpls_calib_t *cal);

/* True when the values are within the sane range for the 1/31 divider. */
bool dpls_calib_valid(const dpls_calib_t *cal);

/* Convert an averaged ADC pin voltage (mV) to line voltage (mV), clamped to
 * the ТЗ measurement range. */
uint16_t dpls_calib_apply(const dpls_calib_t *cal, uint32_t pin_mv);

/* Solve gain and offset from two reference points (known line voltage and the
 * pin voltage measured for it). Returns false on degenerate or out-of-range
 * input, leaving *cal untouched. */
bool dpls_calib_from_two_points(dpls_calib_t *cal,
                                uint32_t line1_mv, uint32_t pin1_mv,
                                uint32_t line2_mv, uint32_t pin2_mv);

#endif
