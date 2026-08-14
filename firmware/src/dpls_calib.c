#include "dpls_calib.h"

void dpls_calib_default(dpls_calib_t *cal)
{
    cal->gain_milli = DPLS_CALIB_NOMINAL_GAIN_MILLI;
    cal->offset_mv = 0;
}

bool dpls_calib_valid(const dpls_calib_t *cal)
{
    return cal->gain_milli >= DPLS_CALIB_GAIN_MIN_MILLI &&
           cal->gain_milli <= DPLS_CALIB_GAIN_MAX_MILLI &&
           cal->offset_mv > -5000 && cal->offset_mv < 5000;
}

uint16_t dpls_calib_apply(const dpls_calib_t *cal, uint32_t pin_mv)
{
    int64_t line_mv = (int64_t)pin_mv * (int64_t)cal->gain_milli / 1000 + cal->offset_mv;
    if (line_mv < 0) line_mv = 0;
    if (line_mv > DPLS_CALIB_MAX_MV) line_mv = DPLS_CALIB_MAX_MV;
    return (uint16_t)line_mv;
}

bool dpls_calib_from_two_points(dpls_calib_t *cal,
                                uint32_t line1_mv, uint32_t pin1_mv,
                                uint32_t line2_mv, uint32_t pin2_mv)
{
    dpls_calib_t candidate;
    int64_t pin_delta = (int64_t)pin2_mv - (int64_t)pin1_mv;
    int64_t line_delta = (int64_t)line2_mv - (int64_t)line1_mv;
    int64_t gain, offset;

    if (pin_delta <= 0 || line_delta <= 0) return false;
    /* Round to nearest milli. */
    gain = (line_delta * 1000 + pin_delta / 2) / pin_delta;
    if (gain < (int64_t)DPLS_CALIB_GAIN_MIN_MILLI || gain > (int64_t)DPLS_CALIB_GAIN_MAX_MILLI)
        return false;
    offset = (int64_t)line1_mv - (int64_t)pin1_mv * gain / 1000;
    candidate.gain_milli = (uint32_t)gain;
    candidate.offset_mv = (int32_t)offset;
    if (!dpls_calib_valid(&candidate)) return false;
    *cal = candidate;
    return true;
}
