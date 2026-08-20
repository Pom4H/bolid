#include "dpls_durable_settings.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static dpls_durable_settings_t record(uint32_t generation,
                                      dpls_durable_settings_state_t state,
                                      const char *name)
{
    dpls_durable_settings_t value;
    memset(&value, 0, sizeof(value));
    value.generation = generation;
    value.state = state;
    if (name) strncpy(value.name, name, sizeof(value.name) - 1u);
    memset(value.salt, (int)(generation & 0xffu), sizeof(value.salt));
    memset(value.verifier, (int)((generation >> 8) & 0xffu), sizeof(value.verifier));
    return value;
}

static void assert_selected_generation(const uint8_t *a, const uint8_t *b, uint32_t generation)
{
    dpls_durable_settings_t selected;
    assert(dpls_durable_settings_select(a, b, &selected, NULL));
    assert(selected.generation == generation);
}

/* Model a target slot that previously contained a valid older generation.
 * Power can disappear after any byte of erase. The still-valid active slot
 * must remain authoritative for every partial erase image. */
static void test_power_cut_during_erase(void)
{
    uint8_t active[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t stale[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t partial[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    dpls_durable_settings_t active_value = record(100u, DPLS_DURABLE_SETTINGS_VALID, "active");
    dpls_durable_settings_t stale_value = record(99u, DPLS_DURABLE_SETTINGS_VALID, "stale");
    size_t cut;

    dpls_durable_settings_encode(active, &active_value);
    dpls_durable_settings_encode(stale, &stale_value);

    for (cut = 0u; cut <= sizeof(partial); ++cut) {
        memcpy(partial, stale, sizeof(partial));
        memset(partial, 0xff, cut);
        assert_selected_generation(active, partial, 100u);
    }
}

/* Model programming the new generation into an erased inactive slot. Only the
 * completely programmed record may become authoritative. */
static void test_power_cut_during_program(void)
{
    uint8_t active[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t intended[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t partial[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    dpls_durable_settings_t active_value = record(200u, DPLS_DURABLE_SETTINGS_VALID, "old");
    dpls_durable_settings_t intended_value = record(201u, DPLS_DURABLE_SETTINGS_VALID, "new");
    size_t cut;

    dpls_durable_settings_encode(active, &active_value);
    dpls_durable_settings_encode(intended, &intended_value);

    for (cut = 0u; cut < sizeof(partial); ++cut) {
        memset(partial, 0xff, sizeof(partial));
        memcpy(partial, intended, cut);
        assert_selected_generation(active, partial, 200u);
    }
    assert_selected_generation(active, intended, 201u);
}

/* Conservative model for an interrupted rewrite where some bytes of a stale
 * target survive. Mixed generations must never win merely because their first
 * bytes look newer. */
static void test_mixed_old_new_target_never_commits(void)
{
    uint8_t active[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t stale[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t intended[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t mixed[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    dpls_durable_settings_t active_value = record(300u, DPLS_DURABLE_SETTINGS_VALID, "active");
    dpls_durable_settings_t stale_value = record(299u, DPLS_DURABLE_SETTINGS_VALID, "stale");
    dpls_durable_settings_t intended_value = record(301u, DPLS_DURABLE_SETTINGS_VALID, "intended");
    size_t cut;

    dpls_durable_settings_encode(active, &active_value);
    dpls_durable_settings_encode(stale, &stale_value);
    dpls_durable_settings_encode(intended, &intended_value);

    for (cut = 0u; cut < sizeof(mixed); ++cut) {
        memcpy(mixed, stale, sizeof(mixed));
        memcpy(mixed, intended, cut);
        assert_selected_generation(active, mixed, 300u);
    }
    assert_selected_generation(active, intended, 301u);
}

static void test_torn_factory_reset_keeps_previous_configuration(void)
{
    uint8_t configured[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t tombstone[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t partial[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    dpls_durable_settings_t configured_value = record(400u, DPLS_DURABLE_SETTINGS_VALID, "configured");
    dpls_durable_settings_t tombstone_value = record(401u, DPLS_DURABLE_SETTINGS_EMPTY, NULL);
    dpls_durable_settings_t selected;
    size_t cut;

    dpls_durable_settings_encode(configured, &configured_value);
    dpls_durable_settings_encode(tombstone, &tombstone_value);
    for (cut = 0u; cut < sizeof(partial); ++cut) {
        memset(partial, 0xff, sizeof(partial));
        memcpy(partial, tombstone, cut);
        assert(dpls_durable_settings_select(configured, partial, &selected, NULL));
        assert(selected.generation == 400u && selected.state == DPLS_DURABLE_SETTINGS_VALID);
    }
    assert(dpls_durable_settings_select(configured, tombstone, &selected, NULL));
    assert(selected.generation == 401u && selected.state == DPLS_DURABLE_SETTINGS_EMPTY);
}

static void test_no_valid_copy_is_detected(void)
{
    uint8_t a[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t b[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    dpls_durable_settings_t av = record(500u, DPLS_DURABLE_SETTINGS_VALID, "a");
    dpls_durable_settings_t bv = record(501u, DPLS_DURABLE_SETTINGS_VALID, "b");
    dpls_durable_settings_t selected;
    size_t i;

    dpls_durable_settings_encode(a, &av);
    dpls_durable_settings_encode(b, &bv);
    for (i = 0u; i < DPLS_DURABLE_SETTINGS_RECORD_SIZE; ++i) {
        uint8_t ca[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
        uint8_t cb[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
        memcpy(ca, a, sizeof(ca));
        memcpy(cb, b, sizeof(cb));
        ca[i] ^= 0x01u;
        cb[i] ^= 0x01u;
        assert(!dpls_durable_settings_select(ca, cb, &selected, NULL));
    }
}

int main(void)
{
    test_power_cut_during_erase();
    test_power_cut_during_program();
    test_mixed_old_new_target_never_commits();
    test_torn_factory_reset_keeps_previous_configuration();
    test_no_valid_copy_is_detected();
    puts("durable settings adversarial power-loss matrix: OK");
    return 0;
}
