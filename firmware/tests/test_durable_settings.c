#include "dpls_durable_settings.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static dpls_durable_settings_t value(uint32_t generation, dpls_durable_settings_state_t state, const char *name)
{
    dpls_durable_settings_t v;
    memset(&v, 0, sizeof(v));
    v.generation = generation;
    v.state = state;
    if (name) strncpy(v.name, name, sizeof(v.name) - 1u);
    memset(v.salt, (int)(generation & 0xffu), sizeof(v.salt));
    memset(v.verifier, (int)((generation + 1u) & 0xffu), sizeof(v.verifier));
    return v;
}

static void test_select_newest_valid(void)
{
    uint8_t a[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t b[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    dpls_durable_settings_t out;
    dpls_durable_settings_t va = value(10u, DPLS_DURABLE_SETTINGS_VALID, "old");
    dpls_durable_settings_t vb = value(11u, DPLS_DURABLE_SETTINGS_VALID, "new");
    uint8_t slot = 0xffu;
    dpls_durable_settings_encode(a, &va);
    dpls_durable_settings_encode(b, &vb);
    assert(dpls_durable_settings_select(a, b, &out, &slot));
    assert(slot == 1u && out.generation == 11u && strcmp(out.name, "new") == 0);
}

static void test_torn_new_record_keeps_old(void)
{
    uint8_t old_raw[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t new_raw[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t torn[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    dpls_durable_settings_t old_value = value(20u, DPLS_DURABLE_SETTINGS_VALID, "old");
    dpls_durable_settings_t new_value = value(21u, DPLS_DURABLE_SETTINGS_VALID, "new");
    dpls_durable_settings_t out;
    size_t cut;

    dpls_durable_settings_encode(old_raw, &old_value);
    dpls_durable_settings_encode(new_raw, &new_value);

    /* Model power loss after every byte of an erase/program operation. Until
     * every byte including CRC is present, reboot selection must keep old. */
    for (cut = 0u; cut < DPLS_DURABLE_SETTINGS_RECORD_SIZE; ++cut) {
        memset(torn, 0xff, sizeof(torn));
        memcpy(torn, new_raw, cut);
        assert(dpls_durable_settings_select(old_raw, torn, &out, NULL));
        assert(out.generation == old_value.generation);
        assert(strcmp(out.name, "old") == 0);
    }

    assert(dpls_durable_settings_select(old_raw, new_raw, &out, NULL));
    assert(out.generation == new_value.generation);
}

static void test_any_single_byte_corruption_rejects_new(void)
{
    uint8_t old_raw[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t new_raw[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t corrupt[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    dpls_durable_settings_t old_value = value(30u, DPLS_DURABLE_SETTINGS_VALID, "old");
    dpls_durable_settings_t new_value = value(31u, DPLS_DURABLE_SETTINGS_VALID, "new");
    dpls_durable_settings_t out;
    size_t i;

    dpls_durable_settings_encode(old_raw, &old_value);
    dpls_durable_settings_encode(new_raw, &new_value);
    for (i = 0u; i < sizeof(corrupt); ++i) {
        memcpy(corrupt, new_raw, sizeof(corrupt));
        corrupt[i] ^= 0x01u;
        assert(dpls_durable_settings_select(old_raw, corrupt, &out, NULL));
        assert(out.generation == old_value.generation);
    }
}

static void test_empty_is_a_durable_tombstone(void)
{
    uint8_t old_raw[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t reset_raw[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    dpls_durable_settings_t old_value = value(40u, DPLS_DURABLE_SETTINGS_VALID, "configured");
    dpls_durable_settings_t reset_value = value(41u, DPLS_DURABLE_SETTINGS_EMPTY, NULL);
    dpls_durable_settings_t out;

    dpls_durable_settings_encode(old_raw, &old_value);
    dpls_durable_settings_encode(reset_raw, &reset_value);
    assert(dpls_durable_settings_select(old_raw, reset_raw, &out, NULL));
    assert(out.generation == 41u && out.state == DPLS_DURABLE_SETTINGS_EMPTY);
}

static void test_generation_wrap(void)
{
    uint8_t a[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t b[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    dpls_durable_settings_t before = value(0xffffffffu, DPLS_DURABLE_SETTINGS_VALID, "before-wrap");
    dpls_durable_settings_t after = value(1u, DPLS_DURABLE_SETTINGS_VALID, "after-wrap");
    dpls_durable_settings_t out;

    assert(dpls_durable_settings_next_generation(0xfffffffeu) == 0xffffffffu);
    assert(dpls_durable_settings_next_generation(0xffffffffu) == 1u);
    dpls_durable_settings_encode(a, &before);
    dpls_durable_settings_encode(b, &after);
    assert(dpls_durable_settings_select(a, b, &out, NULL));
    assert(out.generation == 1u && strcmp(out.name, "after-wrap") == 0);
}

int main(void)
{
    test_select_newest_valid();
    test_torn_new_record_keeps_old();
    test_any_single_byte_corruption_rejects_new();
    test_empty_is_a_durable_tombstone();
    test_generation_wrap();
    puts("durable settings power-cut tests passed");
    return 0;
}
