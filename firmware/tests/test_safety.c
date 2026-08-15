#include "dpls_safety.h"
#include <assert.h>
#include <stdio.h>

static dpls_safety_inputs_t healthy(uint32_t activity) {
    dpls_safety_inputs_t in = { true, true, false, false, activity };
    return in;
}

int main(void) {
    dpls_safety_t s;
    dpls_safety_inputs_t in;
    dpls_safety_init(&s);
    assert(s.mode == DPLS_SAFE_NORMAL);
    assert(dpls_safety_can_enter(DPLS_SAFE_OPEN_T, false));
    dpls_safety_applied(&s, DPLS_SAFE_OPEN_T, 1000u);
    in = healthy(1000u);
    assert(dpls_safety_remaining_seconds(&s, 1000u) == 300u);
    assert(dpls_safety_required_return(&s, &in, 1000u) == DPLS_SAFETY_RETURN_NONE);
    in.authenticated = false;
    assert(dpls_safety_required_return(&s, &in, 1001u) == DPLS_SAFETY_RETURN_SESSION_TIMEOUT);
    in = healthy(1000u);
    in.connected = false;
    assert(dpls_safety_required_return(&s, &in, 1001u) == DPLS_SAFETY_RETURN_DISCONNECT);
    in = healthy(1000u);
    in.reserve_low = true;
    assert(dpls_safety_required_return(&s, &in, 1001u) == DPLS_SAFETY_RETURN_LOW_RESERVE);
    in = healthy(1000u);
    in.real_short = true;
    assert(dpls_safety_required_return(&s, &in, 1001u) == DPLS_SAFETY_RETURN_REAL_SHORT);
    dpls_safety_force_normal(&s);
    assert(s.mode == DPLS_SAFE_NORMAL);
    puts("dpls safety tests passed");
    return 0;
}
