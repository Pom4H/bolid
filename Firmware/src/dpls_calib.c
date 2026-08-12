#include "dpls_calib.h"

void dpls_calib_default(dpls_calib_t *cal)
{
    cal->gain_milli = DPLS_CALIB_NOMINAL_GAIN_MILLI;
    cal->offset_mv = 0;
}

bool dpls_calib_valid_range(const dpls_calib_t *cal,
                            uint32_t gain_min_milli,
                            uint32_t gain_max_milli,
                            int32_t offset_limit_mv)
{
    if (!cal || gain_min_milli > gain_max_milli || offset_limit_mv <= 0)
        return false;
    return cal->gain_milli >= gain_min_milli &&
           cal->gain_milli <= gain_max_milli &&
           cal->offset_mv > -offset_limit_mv &&
           cal->offset_mv < offset_limit_mv;
}

bool dpls_calib_valid(const dpls_calib_t *cal)
{
    return dpls_calib_valid_range(cal,
                                  DPLS_CALIB_GAIN_MIN_MILLI,
                                  DPLS_CALIB_GAIN_MAX_MILLI,
                                  DPLS_CALIB_OFFSET_LIMIT_MV);
}

uint16_t dpls_calib_apply(const dpls_calib_t *cal, uint32_t pin_mv)
{
    int64_t external_mv = (int64_t)pin_mv * (int64_t)cal->gain_milli / 1000 + cal->offset_mv;
    if (external_mv < 0) external_mv = 0;
    if (external_mv > DPLS_CALIB_MAX_MV) external_mv = DPLS_CALIB_MAX_MV;
    return (uint16_t)external_mv;
}

bool dpls_calib_from_two_points_range(dpls_calib_t *cal,
                                      uint32_t external1_mv, uint32_t pin1_mv,
                                      uint32_t external2_mv, uint32_t pin2_mv,
                                      uint32_t gain_min_milli,
                                      uint32_t gain_max_milli,
                                      int32_t offset_limit_mv)
{
    dpls_calib_t candidate;
    int64_t pin_delta = (int64_t)pin2_mv - (int64_t)pin1_mv;
    int64_t external_delta = (int64_t)external2_mv - (int64_t)external1_mv;
    int64_t gain;
    int64_t offset;

    if (!cal || pin_delta <= 0 || external_delta <= 0)
        return false;

    /* Round to nearest milli. */
    gain = (external_delta * 1000 + pin_delta / 2) / pin_delta;
    if (gain < (int64_t)gain_min_milli || gain > (int64_t)gain_max_milli)
        return false;

    offset = (int64_t)external1_mv - (int64_t)pin1_mv * gain / 1000;
    if (offset < INT32_MIN || offset > INT32_MAX)
        return false;

    candidate.gain_milli = (uint32_t)gain;
    candidate.offset_mv = (int32_t)offset;
    if (!dpls_calib_valid_range(&candidate, gain_min_milli, gain_max_milli, offset_limit_mv))
        return false;

    *cal = candidate;
    return true;
}

bool dpls_calib_from_two_points(dpls_calib_t *cal,
                                uint32_t line1_mv, uint32_t pin1_mv,
                                uint32_t line2_mv, uint32_t pin2_mv)
{
    return dpls_calib_from_two_points_range(cal,
                                            line1_mv, pin1_mv,
                                            line2_mv, pin2_mv,
                                            DPLS_CALIB_GAIN_MIN_MILLI,
                                            DPLS_CALIB_GAIN_MAX_MILLI,
                                            DPLS_CALIB_OFFSET_LIMIT_MV);
}
