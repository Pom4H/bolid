#ifndef DPLS_DURABLE_SETTINGS_H
#define DPLS_DURABLE_SETTINGS_H

#include <stdbool.h>
#include <stdint.h>

#define DPLS_DURABLE_SETTINGS_NAME_SIZE 32u
#define DPLS_DURABLE_SETTINGS_SALT_SIZE 16u
#define DPLS_DURABLE_SETTINGS_VERIFIER_SIZE 32u
#define DPLS_DURABLE_SETTINGS_RECORD_SIZE 94u

typedef enum {
    DPLS_DURABLE_SETTINGS_EMPTY = 0,
    DPLS_DURABLE_SETTINGS_VALID = 1,
} dpls_durable_settings_state_t;

typedef struct {
    uint32_t generation;
    dpls_durable_settings_state_t state;
    char name[DPLS_DURABLE_SETTINGS_NAME_SIZE];
    uint8_t salt[DPLS_DURABLE_SETTINGS_SALT_SIZE];
    uint8_t verifier[DPLS_DURABLE_SETTINGS_VERIFIER_SIZE];
} dpls_durable_settings_t;

/* The on-flash record is byte-defined, never a compiler struct layout. */
void dpls_durable_settings_encode(
    uint8_t out[DPLS_DURABLE_SETTINGS_RECORD_SIZE],
    const dpls_durable_settings_t *settings
);

bool dpls_durable_settings_decode(
    const uint8_t raw[DPLS_DURABLE_SETTINGS_RECORD_SIZE],
    dpls_durable_settings_t *out
);

/* Select the newest CRC-valid record using wrap-safe generation ordering.
 * Returns false only when neither slot contains a valid record. */
bool dpls_durable_settings_select(
    const uint8_t slot_a[DPLS_DURABLE_SETTINGS_RECORD_SIZE],
    const uint8_t slot_b[DPLS_DURABLE_SETTINGS_RECORD_SIZE],
    dpls_durable_settings_t *out,
    uint8_t *selected_slot
);

uint32_t dpls_durable_settings_next_generation(uint32_t current);

#endif
