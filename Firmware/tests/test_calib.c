#include "dpls_calib.h"
#include <assert.h>
#include <stdio.h>

int main(void)
{
    dpls_calib_t cal;

    /* Nominal 1/31 divider: pin_mv * 31 = line_mv. */
    dpls_calib_default(&cal);
    assert(dpls_calib_valid(&cal));
    assert(dpls_calib_apply(&cal, 0u) == 0u);
    assert(dpls_calib_apply(&cal, 200u) == 6200u);
    assert(dpls_calib_apply(&cal, 871u) == 27001u);  /* ~27 V */

    /* Clamp to the ТЗ 0…30 V window. */
    assert(dpls_calib_apply(&cal, 970u) == 30000u);   /* 30070 -> clamp */
    assert(dpls_calib_apply(&cal, 5000u) == 30000u);

    /* Offset is applied and cannot push the result below zero. */
    cal.gain_milli = 31000u;
    cal.offset_mv = -100;
    assert(dpls_calib_apply(&cal, 200u) == 6100u);
    cal.offset_mv = -100000; /* absurd, but apply must still clamp at 0 */
    assert(dpls_calib_apply(&cal, 200u) == 0u);

    /* Two-point solve recovers a clean gain/offset. */
    assert(dpls_calib_from_two_points(&cal, 6200u, 200u, 27900u, 900u));
    assert(cal.gain_milli == 31000u);
    assert(cal.offset_mv == 0);
    assert(dpls_calib_apply(&cal, 200u) == 6200u);
    assert(dpls_calib_apply(&cal, 900u) == 27900u);

    /* Two-point solve with a real offset. */
    assert(dpls_calib_from_two_points(&cal, 5100u, 200u, 27900u, 900u));
    /* gain = 22800*1000/700 = 32571; offset = 5100 - 200*32571/1000 = -1414 */
    assert(cal.gain_milli == 32571u);
    assert(cal.offset_mv == -1414);

    /* Degenerate and out-of-range inputs are rejected, leaving cal intact. */
    dpls_calib_t saved = cal;
    assert(!dpls_calib_from_two_points(&cal, 6200u, 200u, 27900u, 200u)); /* pin delta 0 */
    assert(!dpls_calib_from_two_points(&cal, 6200u, 200u, 5000u, 900u));  /* line goes down */
    assert(!dpls_calib_from_two_points(&cal, 100u, 200u, 200u, 900u));    /* gain too small */
    assert(cal.gain_milli == saved.gain_milli && cal.offset_mv == saved.offset_mv);

    printf("test_calib: all assertions passed\n");
    return 0;
}
