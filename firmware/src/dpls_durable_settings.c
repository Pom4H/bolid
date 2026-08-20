#include "dpls_durable_settings.h"
#include "dpls_protocol.h"

#include <stddef.h>
#include <string.h>

#define DPLS_DURABLE_MAGIC 0x32535044u /* "DPS2" little-endian */
#define OFF_MAGIC 0u
#define OFF_GENERATION 4u
#define OFF_STATE 8u
#define OFF_RESERVED 9u
#define OFF_NAME 12u
#define OFF_SALT 44u
#define OFF_VERIFIER 60u
#define OFF_CRC 92u

static uint16_t rd16(const uint8_t *p)
{
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static uint32_t rd32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void wr16(uint8_t *p, uint16_t value)
{
    p[0] = (uint8_t)value;
    p[1] = (uint8_t)(value >> 8);
}

static void wr32(uint8_t *p, uint32_t value)
{
    p[0] = (uint8_t)value;
    p[1] = (uint8_t)(value >> 8);
    p[2] = (uint8_t)(value >> 16);
    p[3] = (uint8_t)(value >> 24);
}

static bool generation_newer(uint32_t left, uint32_t right)
{
    return (int32_t)(left - right) > 0;
}

uint32_t dpls_durable_settings_next_generation(uint32_t current)
{
    uint32_t next = current + 1u;
    return next == 0u ? 1u : next;
}

void dpls_durable_settings_encode(
    uint8_t out[DPLS_DURABLE_SETTINGS_RECORD_SIZE],
    const dpls_durable_settings_t *settings
) {
    uint16_t crc;
    memset(out, 0, DPLS_DURABLE_SETTINGS_RECORD_SIZE);
    wr32(out + OFF_MAGIC, DPLS_DURABLE_MAGIC);
    wr32(out + OFF_GENERATION, settings->generation);
    out[OFF_STATE] = (uint8_t)settings->state;
    memcpy(out + OFF_NAME, settings->name, DPLS_DURABLE_SETTINGS_NAME_SIZE);
    memcpy(out + OFF_SALT, settings->salt, DPLS_DURABLE_SETTINGS_SALT_SIZE);
    memcpy(out + OFF_VERIFIER, settings->verifier, DPLS_DURABLE_SETTINGS_VERIFIER_SIZE);
    crc = dpls_crc16(out, OFF_CRC);
    wr16(out + OFF_CRC, crc);
}

bool dpls_durable_settings_decode(
    const uint8_t raw[DPLS_DURABLE_SETTINGS_RECORD_SIZE],
    dpls_durable_settings_t *out
) {
    uint32_t generation;
    uint8_t state;
    uint16_t stored_crc;
    if (!raw || !out) return false;
    if (rd32(raw + OFF_MAGIC) != DPLS_DURABLE_MAGIC) return false;
    stored_crc = rd16(raw + OFF_CRC);
    if (stored_crc != dpls_crc16(raw, OFF_CRC)) return false;
    generation = rd32(raw + OFF_GENERATION);
    if (generation == 0u) return false;
    state = raw[OFF_STATE];
    if (state > (uint8_t)DPLS_DURABLE_SETTINGS_VALID) return false;
    if (raw[OFF_RESERVED] != 0u || raw[OFF_RESERVED + 1u] != 0u || raw[OFF_RESERVED + 2u] != 0u)
        return false;

    memset(out, 0, sizeof(*out));
    out->generation = generation;
    out->state = (dpls_durable_settings_state_t)state;
    memcpy(out->name, raw + OFF_NAME, DPLS_DURABLE_SETTINGS_NAME_SIZE);
    memcpy(out->salt, raw + OFF_SALT, DPLS_DURABLE_SETTINGS_SALT_SIZE);
    memcpy(out->verifier, raw + OFF_VERIFIER, DPLS_DURABLE_SETTINGS_VERIFIER_SIZE);

    if (out->state == DPLS_DURABLE_SETTINGS_VALID) {
        /* Every writer zero-pads the fixed name field. A missing terminator is a
         * torn/foreign record even if a coincidental CRC happened to match. */
        if (out->name[0] == '\0' || memchr(out->name, '\0', sizeof(out->name)) == NULL)
            return false;
    }
    return true;
}

bool dpls_durable_settings_select(
    const uint8_t slot_a[DPLS_DURABLE_SETTINGS_RECORD_SIZE],
    const uint8_t slot_b[DPLS_DURABLE_SETTINGS_RECORD_SIZE],
    dpls_durable_settings_t *out,
    uint8_t *selected_slot
) {
    dpls_durable_settings_t a;
    dpls_durable_settings_t b;
    bool a_valid = dpls_durable_settings_decode(slot_a, &a);
    bool b_valid = dpls_durable_settings_decode(slot_b, &b);

    if (!a_valid && !b_valid) return false;
    if (b_valid && (!a_valid || generation_newer(b.generation, a.generation))) {
        *out = b;
        if (selected_slot) *selected_slot = 1u;
        return true;
    }
    *out = a;
    if (selected_slot) *selected_slot = 0u;
    return true;
}
