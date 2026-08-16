#include "dpls_safety.h"
#include <assert.h>
#include <stdio.h>

static dpls_safety_inputs_t healthy(uint32_t activity) {
    dpls_safety_inputs_t in = { true, true, false, false, activity };
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
    in.reserve_low = true;
    assert(dpls_safety_required_return(&s, &in, 1001u) == DPLS_SAFETY_RETURN_LOW_RESERVE);
    in.real_short = true;
    assert(dpls_safety_required_return(&s, &in, 1001u) == DPLS_SAFETY_RETURN_REAL_SHORT);
    in.connected = false;
    assert(dpls_safety_required_return(&s, &in, 1001u) == DPLS_SAFETY_RETURN_DISCONNECT);

    in = healthy(1000u);
    assert(dpls_safety_required_return(&s, &in, 1000u + DPLS_SAFETY_MODE_MAX_MS) ==
           DPLS_SAFETY_RETURN_MODE_TIMEOUT);
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
    test_deadline_wrap();
    puts("dpls safety tests passed");
    return 0;
}
