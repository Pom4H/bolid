#include "dpls_safety.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static dpls_safety_inputs_t healthy(uint32_t activity) {
    dpls_safety_inputs_t in;
    memset(&in, 0, sizeof(in));
    in.connected = true;
    in.authenticated = true;
    in.measurements_ready = true;
    in.last_authenticated_activity_ms = activity;
    return in;
}

static void test_mode_lifecycle(void) {
    dpls_safety_t s;
    dpls_safety_inputs_t in;
    dpls_safety_init(&s);
    assert(s.mode == DPLS_SAFE_NORMAL && s.revision == 1u);
    assert(!dpls_safety_can_enter((dpls_safety_mode_t)99, false));
    assert(!dpls_safety_can_enter(DPLS_SAFE_OPEN_T, true));
    assert(dpls_safety_can_enter(DPLS_SAFE_OPEN_T, false));
    dpls_safety_commit_mode(&s, DPLS_SAFE_OPEN_T, 1000u);
    assert(s.revision == 2u);
    dpls_safety_commit_mode(&s, DPLS_SAFE_OPEN_T, 2000u);
    assert(s.revision == 2u);
    in = healthy(1000u);
    assert(dpls_safety_remaining_seconds(&s, 2000u) == 300u);
    assert(dpls_safety_required_return(&s, &in, 2000u) == DPLS_SAFETY_RETURN_NONE);
    dpls_safety_force_normal(&s);
    assert(s.mode == DPLS_SAFE_NORMAL && s.revision == 3u);
    dpls_safety_force_normal(&s);
    assert(s.revision == 3u);
}

static void test_return_precedence(void) {
    dpls_safety_t s;
    dpls_safety_inputs_t in;
    dpls_safety_init(&s);
    dpls_safety_commit_mode(&s, DPLS_SAFE_OPEN_T, 1000u);

    in = healthy(1000u);
    in.authenticated = false;
    assert(dpls_safety_required_return(&s, &in, 1001u) == DPLS_SAFETY_RETURN_SESSION_TIMEOUT);
    in = healthy(1000u);
    in.reserve_low = true;
    assert(dpls_safety_required_return(&s, &in, 1001u) == DPLS_SAFETY_RETURN_LOW_RESERVE);
    in = healthy(1000u);
    in.real_short = true;
    assert(dpls_safety_required_return(&s, &in, 1001u) == DPLS_SAFETY_RETURN_REAL_SHORT);
    in = healthy(1000u);
    in.measurements_ready = false;
    assert(dpls_safety_required_return(&s, &in, 1001u) == DPLS_SAFETY_RETURN_MEASUREMENT_LOST);
    in = healthy(1000u);
    in.connected = false;
    in.measurements_ready = false;
    assert(dpls_safety_required_return(&s, &in, 1001u) == DPLS_SAFETY_RETURN_DISCONNECT);

    in = healthy(1000u);
    assert(dpls_safety_required_return(&s, &in, 1000u + DPLS_SAFETY_MODE_MAX_MS) ==
           DPLS_SAFETY_RETURN_MODE_TIMEOUT);
}

/* Exhaust the boolean state space for every dangerous mode. This is the small
 * model checker for the central safety invariant: the only steady dangerous
 * state is connected + authenticated + fresh measurements + no low reserve +
 * no real short + live deadlines. */
static void test_dangerous_state_space(void) {
    dpls_safety_t s;
    dpls_safety_inputs_t in;
    unsigned mode, connected, authenticated, measured, low, real_short;
    for (mode = DPLS_SAFE_OPEN_T; mode <= DPLS_SAFE_SHORT_T; ++mode) {
        for (connected = 0u; connected <= 1u; ++connected)
        for (authenticated = 0u; authenticated <= 1u; ++authenticated)
        for (measured = 0u; measured <= 1u; ++measured)
        for (low = 0u; low <= 1u; ++low)
        for (real_short = 0u; real_short <= 1u; ++real_short) {
            dpls_safety_init(&s);
            dpls_safety_commit_mode(&s, (dpls_safety_mode_t)mode, 1000u);
            in = healthy(1000u);
            in.connected = connected != 0u;
            in.authenticated = authenticated != 0u;
            in.measurements_ready = measured != 0u;
            in.reserve_low = low != 0u;
            in.real_short = real_short != 0u;
            if (connected && authenticated && measured && !low && !real_short) {
                assert(dpls_safety_required_return(&s, &in, 1001u) == DPLS_SAFETY_RETURN_NONE);
            } else {
                assert(dpls_safety_required_return(&s, &in, 1001u) != DPLS_SAFETY_RETURN_NONE);
            }
        }
    }
}

static void test_normal_is_always_fail_safe(void) {
    dpls_safety_t s;
    dpls_safety_inputs_t in;
    unsigned mask;
    dpls_safety_init(&s);
    for (mask = 0u; mask < 32u; ++mask) {
        memset(&in, 0, sizeof(in));
        in.connected = (mask & 1u) != 0u;
        in.authenticated = (mask & 2u) != 0u;
        in.measurements_ready = (mask & 4u) != 0u;
        in.reserve_low = (mask & 8u) != 0u;
        in.real_short = (mask & 16u) != 0u;
        assert(dpls_safety_required_return(&s, &in, 1234u) == DPLS_SAFETY_RETURN_NONE);
    }
}

static void test_deadline_wrap(void) {
    dpls_safety_t s;
    dpls_safety_inputs_t in = healthy(0xfffffff0u);
    dpls_safety_init(&s);
    dpls_safety_commit_mode(&s, DPLS_SAFE_SHORT_1, 0xfffffff0u);
    assert(dpls_safety_remaining_seconds(&s, 0xfffffff0u) == 300u);
    assert(dpls_safety_required_return(&s, &in, 0x10u) == DPLS_SAFETY_RETURN_NONE);
}

int main(void) {
    test_mode_lifecycle();
    test_return_precedence();
    test_dangerous_state_space();
    test_normal_is_always_fail_safe();
    test_deadline_wrap();
    puts("dpls safety invariant state-space tests passed");
    return 0;
}
