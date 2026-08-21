#include "dpls_storage_actor.h"

#include <assert.h>
#include <stdio.h>

static void test_connected_write_drains_before_flash(void)
{
    dpls_storage_actor_t a;
    dpls_storage_effects_t fx;
    dpls_storage_actor_init(&a, true);

    fx = dpls_storage_actor_reduce(&a, DPLS_STORAGE_EVT_WRITE_REQUESTED);
    assert(a.phase == DPLS_STORAGE_DRAINING);
    assert(a.pending && a.link_active);
    assert(fx.request_disconnect);
    assert(!fx.commit);
    assert(!dpls_storage_actor_flash_allowed(&a));

    fx = dpls_storage_actor_reduce(&a, DPLS_STORAGE_EVT_TX_IDLE);
    assert(fx.request_disconnect);
    assert(!fx.commit);

    fx = dpls_storage_actor_reduce(&a, DPLS_STORAGE_EVT_LINK_DOWN);
    assert(a.phase == DPLS_STORAGE_FLASH);
    assert(!a.link_active);
    assert(fx.disable_advertising);
    assert(fx.commit);
    assert(dpls_storage_actor_flash_allowed(&a));

    fx = dpls_storage_actor_reduce(&a, DPLS_STORAGE_EVT_COMMIT_OK);
    assert(a.phase == DPLS_STORAGE_RADIO);
    assert(!a.pending);
    assert(fx.enable_advertising);
    assert(dpls_storage_actor_advertising_allowed(&a));
}

static void test_disconnected_write_enters_flash_window_immediately(void)
{
    dpls_storage_actor_t a;
    dpls_storage_effects_t fx;
    dpls_storage_actor_init(&a, false);

    fx = dpls_storage_actor_reduce(&a, DPLS_STORAGE_EVT_WRITE_REQUESTED);
    assert(a.phase == DPLS_STORAGE_FLASH);
    assert(fx.disable_advertising);
    assert(fx.commit);
    assert(!fx.request_disconnect);
}

static void test_commit_failure_never_reopens_radio(void)
{
    dpls_storage_actor_t a;
    dpls_storage_effects_t fx;
    dpls_storage_actor_init(&a, false);
    (void)dpls_storage_actor_reduce(&a, DPLS_STORAGE_EVT_WRITE_REQUESTED);

    fx = dpls_storage_actor_reduce(&a, DPLS_STORAGE_EVT_COMMIT_RETRY);
    assert(a.phase == DPLS_STORAGE_FLASH);
    assert(a.pending);
    assert(fx.commit);
    assert(fx.disable_advertising);
    assert(!fx.enable_advertising);

    fx = dpls_storage_actor_reduce(&a, DPLS_STORAGE_EVT_COMMIT_FATAL);
    assert(a.phase == DPLS_STORAGE_FAULT);
    assert(fx.fail_safe);
    assert(fx.disable_advertising);
    assert(!dpls_storage_actor_advertising_allowed(&a));
}

static void test_all_phase_event_pairs_preserve_flash_invariant(void)
{
    dpls_storage_phase_t phase;
    int pending;
    int link;
    int event;
    unsigned cases = 0;

    for (phase = DPLS_STORAGE_RADIO; phase <= DPLS_STORAGE_FAULT; ++phase) {
        for (pending = 0; pending <= 1; ++pending) {
            for (link = 0; link <= 1; ++link) {
                for (event = DPLS_STORAGE_EVT_WRITE_REQUESTED;
                     event <= DPLS_STORAGE_EVT_RESET; ++event) {
                    dpls_storage_actor_t a;
                    a.phase = phase;
                    a.pending = pending != 0;
                    a.link_active = link != 0;
                    (void)dpls_storage_actor_reduce(&a, (dpls_storage_event_t)event);
                    ++cases;
                    if (dpls_storage_actor_flash_allowed(&a)) {
                        assert(a.phase == DPLS_STORAGE_FLASH);
                        assert(a.pending);
                        assert(!a.link_active);
                    }
                    if (dpls_storage_actor_advertising_allowed(&a)) {
                        assert(a.phase == DPLS_STORAGE_RADIO);
                        assert(!a.pending);
                        assert(!a.link_active);
                    }
                }
            }
        }
    }
    assert(cases == 4u * 2u * 2u * 7u);
}

int main(void)
{
    test_connected_write_drains_before_flash();
    test_disconnected_write_enters_flash_window_immediately();
    test_commit_failure_never_reopens_radio();
    test_all_phase_event_pairs_preserve_flash_invariant();
    puts("storage actor tests: ok");
    return 0;
}
