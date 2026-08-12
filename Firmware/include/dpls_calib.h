#ifndef DPLS_CALIB_H
#define DPLS_CALIB_H

#include <stdbool.h>
#include <stdint.h>

/* Linear calibration from ADC pin voltage to external voltage, both in mV.
 * The DPLS line inputs use nominal 1/31 dividers (3.0 MΩ / 100 kΩ). Reserve
 * voltage is a different divider and therefore validates against its own bounds
 * in the PHY6252 ADC adapter rather than pretending every channel is 31x. */

#define DPLS_CALIB_NOMINAL_GAIN_MILLI 31000u
#define DPLS_CALIB_MAX_MV 30000u          /* ТЗ 4.3.7 range 0…30 V */
#define DPLS_CALIB_GAIN_MIN_MILLI 20000u  /* sane bounds for 1/31 line divider */
#define DPLS_CALIB_GAIN_MAX_MILLI 45000u
#define DPLS_CALIB_OFFSET_LIMIT_MV 5000

typedef struct {
    uint32_t gain_milli;  /* external_mv per pin_mv, scaled by 1000 */
    int32_t offset_mv;    /* additive correction after gain */
} dpls_calib_t;

void dpls_calib_default(dpls_calib_t *cal);

/* Generic validator for channels whose divider differs from the DPLS 1/31
 * line divider (notably the reserve accumulator). offset_limit_mv is exclusive
 * and must be positive. */
bool dpls_calib_valid_range(const dpls_calib_t *cal,
                            uint32_t gain_min_milli,
                            uint32_t gain_max_milli,
                            int32_t offset_limit_mv);

/* Convenience validator for a DPLS line channel. */
bool dpls_calib_valid(const dpls_calib_t *cal);

/* Convert an ADC pin voltage (mV) to external voltage (mV), clamped to the ТЗ
 * measurement range. Averaging may be performed before or after this operation
 * by the hardware adapter; the transform itself is deterministic. */
uint16_t dpls_calib_apply(const dpls_calib_t *cal, uint32_t pin_mv);

/* Generic bounded two-point solver. */
bool dpls_calib_from_two_points_range(dpls_calib_t *cal,
                                      uint32_t external1_mv, uint32_t pin1_mv,
                                      uint32_t external2_mv, uint32_t pin2_mv,
                                      uint32_t gain_min_milli,
                                      uint32_t gain_max_milli,
                                      int32_t offset_limit_mv);

/* Two-point solver using the normal 1/31 DPLS line bounds. */
bool dpls_calib_from_two_points(dpls_calib_t *cal,
                                uint32_t line1_mv, uint32_t pin1_mv,
                                uint32_t line2_mv, uint32_t pin2_mv);

#endif
