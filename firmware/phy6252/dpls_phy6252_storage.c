#include "dpls_phy6252_storage.h"

#include "dpls_phy6252_supervisor.h"
#include "osal_snv.h"
#include <stddef.h>
#include <string.h>

#define DPLS_SETTINGS_MAGIC 0x534C5044u
#define DPLS_CALIB_MAGIC 0x434C5044u
#define DPLS_AUTH_LOCK_MAGIC 0x4B434C44u /* "DLCK" */
#define DPLS_SETTINGS_SNV_ID 0x80u
#define DPLS_SETTINGS_STATE_SNV_ID 0x81u
#define DPLS_CALIB_SNV_ID 0x83u
#define DPLS_AUTH_LOCK_SNV_ID 0x84u
#define DPLS_JOURNAL_FIRST_SNV_ID 0x90u
#define DPLS_JOURNAL_EVENTS_PER_BLOCK 10u
#define DPLS_JOURNAL_RECORD_SIZE 12u
#define DPLS_JOURNAL_BLOCK_COUNT (DPLS_EVENT_CAPACITY / DPLS_JOURNAL_EVENTS_PER_BLOCK)
#define DPLS_JOURNAL_BLOCK_SIZE (DPLS_JOURNAL_EVENTS_PER_BLOCK * DPLS_JOURNAL_RECORD_SIZE)
#define DPLS_PENDING_EVENT_CAPACITY 32u
#define DPLS_NAME_SIZE 32u
#define DPLS_SETTINGS_EMPTY_MARKER 0x45u
#define DPLS_SETTINGS_VALID_MARKER 0x56u
#define DPLS_VCAP_NOMINAL_GAIN_MILLI 2000u

typedef struct {
    uint32_t magic;
    char name[DPLS_NAME_SIZE];
    uint8_t salt[DPLS_AUTH_SALT_SIZE];
    uint8_t verifier[DPLS_AUTH_PROOF_SIZE];
    uint16_t crc;
} dpls_settings_t;

typedef struct {
    uint32_t magic;
    uint8_t locked;
    uint8_t reserved;
    uint16_t crc;
} dpls_auth_lock_t;

typedef struct {
    uint32_t magic;
    uint32_t line_gain_milli;
    int32_t line_offset_mv;
    uint32_t vcap_gain_milli;
    int32_t vcap_offset_mv;
    uint16_t crc;
} dpls_calib_nv_t;

static dpls_settings_t settings;
static dpls_settings_state_t settings_state = DPLS_SETTINGS_EMPTY;
static bool link_active;
static dpls_event_t pending_events[DPLS_PENDING_EVENT_CAPACITY];
static uint8_t pending_event_count;
static uint8_t journal_block_cache[DPLS_JOURNAL_BLOCK_SIZE];
static uint8_t journal_cached_block = 0xffu;
static uint8_t journal_service_block[DPLS_JOURNAL_BLOCK_SIZE];

#if DPLS_EVENT_CAPACITY != 200u
#error "PHY6252 journal layout is defined for exactly 200 events"
#endif

static uint8_t snv_write_bounded(osalSnvId_t id, osalSnvLen_t len, void *data)
{
    uint8_t rc;
    dpls_phy6252_supervisor_blocking_io_begin();
    rc = osal_snv_write(id, len, data);
    dpls_phy6252_supervisor_blocking_io_end();
    return rc;
}

static uint32_t journal_rd32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void journal_wr32(uint8_t *p, uint32_t value)
{
    p[0] = (uint8_t)value;
    p[1] = (uint8_t)(value >> 8);
    p[2] = (uint8_t)(value >> 16);
    p[3] = (uint8_t)(value >> 24);
}

static bool journal_decode_record(const uint8_t record[DPLS_JOURNAL_RECORD_SIZE], dpls_event_t *event)
{
    uint16_t stored_crc = (uint16_t)(record[10] | ((uint16_t)record[11] << 8));
    if (stored_crc != dpls_crc16(record, 10u)) return false;
    event->sequence = journal_rd32(record);
    event->timestamp_seconds = journal_rd32(record + 4);
    event->event_type = record[8];
    event->parameter = record[9];
    return event->sequence != 0u && event->event_type >= 1u && event->event_type <= 14u;
}

static void journal_encode_record(uint8_t record[DPLS_JOURNAL_RECORD_SIZE], const dpls_event_t *event)
{
    uint16_t crc;
    journal_wr32(record, event->sequence);
    journal_wr32(record + 4, event->timestamp_seconds);
    record[8] = event->event_type;
    record[9] = event->parameter;
    crc = dpls_crc16(record, 10u);
    record[10] = (uint8_t)crc;
    record[11] = (uint8_t)(crc >> 8);
}

static uint8_t *journal_load_block(uint8_t block_index)
{
    if (journal_cached_block == block_index) return journal_block_cache;
    memset(journal_block_cache, 0, sizeof(journal_block_cache));
    (void)osal_snv_read((osalSnvId_t)(DPLS_JOURNAL_FIRST_SNV_ID + block_index),
                        (osalSnvLen_t)DPLS_JOURNAL_BLOCK_SIZE, journal_block_cache);
    journal_cached_block = block_index;
    return journal_block_cache;
}

static bool journal_record_in_slot(const uint8_t *record, uint16_t slot, dpls_event_t *event)
{
    return journal_decode_record(record, event) &&
           (uint16_t)((event->sequence - 1u) % DPLS_EVENT_CAPACITY) == slot;
}

static uint32_t journal_latest_sequence(void)
{
    dpls_event_t event;
    uint32_t max_sequence = 0;
    uint16_t block_index, record_index;
    for (block_index = 0; block_index < DPLS_JOURNAL_BLOCK_COUNT; ++block_index) {
        uint8_t *block;
        dpls_phy6252_supervisor_checkpoint();
        block = journal_load_block((uint8_t)block_index);
        for (record_index = 0; record_index < DPLS_JOURNAL_EVENTS_PER_BLOCK; ++record_index) {
            uint16_t slot = (uint16_t)(block_index * DPLS_JOURNAL_EVENTS_PER_BLOCK + record_index);
            if (journal_record_in_slot(block + record_index * DPLS_JOURNAL_RECORD_SIZE,
                                       slot, &event) && event.sequence > max_sequence)
                max_sequence = event.sequence;
        }
    }
    return max_sequence;
}

static uint16_t journal_contiguous_count(uint32_t max_sequence)
{
    uint8_t present[(DPLS_EVENT_CAPACITY + 7u) / 8u];
    dpls_event_t event;
    uint16_t block_index, record_index, count = 0;
    memset(present, 0, sizeof(present));
    for (block_index = 0; block_index < DPLS_JOURNAL_BLOCK_COUNT; ++block_index) {
        uint8_t *block;
        dpls_phy6252_supervisor_checkpoint();
        block = journal_load_block((uint8_t)block_index);
        for (record_index = 0; record_index < DPLS_JOURNAL_EVENTS_PER_BLOCK; ++record_index) {
            uint16_t slot = (uint16_t)(block_index * DPLS_JOURNAL_EVENTS_PER_BLOCK + record_index);
            uint32_t age;
            if (!journal_record_in_slot(block + record_index * DPLS_JOURNAL_RECORD_SIZE,
                                        slot, &event) || event.sequence > max_sequence)
                continue;
            age = max_sequence - event.sequence;
            if (age < DPLS_EVENT_CAPACITY)
                present[age / 8u] |= (uint8_t)(1u << (age % 8u));
        }
    }
    while (count < DPLS_EVENT_CAPACITY &&
           (present[count / 8u] & (uint8_t)(1u << (count % 8u)))) ++count;
    return count;
}

static void classify_settings(void)
{
    uint16_t expected_crc;
    uint8_t marker = 0;
    uint8_t state_read;
    memset(&settings, 0, sizeof(settings));
    state_read = osal_snv_read(DPLS_SETTINGS_SNV_ID, sizeof(settings), &settings);
    if (state_read != SUCCESS) {
        settings_state = DPLS_SETTINGS_EMPTY;
        return;
    }
    expected_crc = dpls_crc16((const uint8_t *)&settings, offsetof(dpls_settings_t, crc));
    if (settings.magic == DPLS_SETTINGS_MAGIC && settings.crc == expected_crc) {
        settings_state = DPLS_SETTINGS_VALID;
        return;
    }
    if (osal_snv_read(DPLS_SETTINGS_STATE_SNV_ID, sizeof(marker), &marker) == SUCCESS &&
        marker == DPLS_SETTINGS_EMPTY_MARKER) {
        settings_state = DPLS_SETTINGS_EMPTY;
        return;
    }
    settings_state = DPLS_SETTINGS_CORRUPT;
    memset(&settings, 0, sizeof(settings));
}

static bool persist_current_settings(void)
{
    dpls_settings_t verified;
    uint8_t marker = DPLS_SETTINGS_VALID_MARKER;
    settings.magic = DPLS_SETTINGS_MAGIC;
    settings.crc = dpls_crc16((const uint8_t *)&settings, offsetof(dpls_settings_t, crc));
    if (snv_write_bounded(DPLS_SETTINGS_SNV_ID, sizeof(settings), &settings) != SUCCESS ||
        osal_snv_read(DPLS_SETTINGS_SNV_ID, sizeof(verified), &verified) != SUCCESS ||
        verified.magic != DPLS_SETTINGS_MAGIC ||
        verified.crc != dpls_crc16((const uint8_t *)&verified, offsetof(dpls_settings_t, crc)) ||
        snv_write_bounded(DPLS_SETTINGS_STATE_SNV_ID, sizeof(marker), &marker) != SUCCESS) {
        memset(&settings, 0, sizeof(settings));
        settings_state = DPLS_SETTINGS_CORRUPT;
        return false;
    }
    settings_state = DPLS_SETTINGS_VALID;
    return true;
}

void dpls_phy6252_storage_init(void)
{
    pending_event_count = 0u;
    journal_cached_block = 0xffu;
    link_active = false;
    classify_settings();
}

void dpls_phy6252_storage_set_link_active(bool active)
{
    link_active = active;
}

dpls_settings_state_t dpls_phy6252_storage_settings_state(void *context)
{
    (void)context;
    return settings_state;
}

void dpls_phy6252_storage_settings_salt(void *context, uint8_t out[DPLS_AUTH_SALT_SIZE])
{
    (void)context;
    if (settings_state == DPLS_SETTINGS_VALID) memcpy(out, settings.salt, DPLS_AUTH_SALT_SIZE);
    else memset(out, 0, DPLS_AUTH_SALT_SIZE);
}

bool dpls_phy6252_storage_write_settings(void *context, const char *name,
                                         const uint8_t salt[16], const uint8_t verifier[32])
{
    size_t name_length;
    (void)context;
    memset(&settings, 0, sizeof(settings));
    name_length = strlen(name);
    if (name_length >= DPLS_NAME_SIZE) name_length = DPLS_NAME_SIZE - 1u;
    memcpy(settings.name, name, name_length);
    memcpy(settings.salt, salt, DPLS_AUTH_SALT_SIZE);
    memcpy(settings.verifier, verifier, DPLS_AUTH_PROOF_SIZE);
    return persist_current_settings();
}

void dpls_phy6252_storage_settings_name(void *context, char out[DPLS_NAME_MAX + 1u])
{
    (void)context;
    if (settings_state == DPLS_SETTINGS_VALID) {
        memcpy(out, settings.name, DPLS_NAME_MAX);
        out[DPLS_NAME_MAX] = '\0';
    } else {
        out[0] = '\0';
    }
}

bool dpls_phy6252_storage_set_name(void *context, const char *name)
{
    size_t name_length;
    (void)context;
    if (settings_state != DPLS_SETTINGS_VALID) return false;
    name_length = strlen(name);
    if (name_length >= DPLS_NAME_SIZE) name_length = DPLS_NAME_SIZE - 1u;
    memset(settings.name, 0, sizeof(settings.name));
    memcpy(settings.name, name, name_length);
    return persist_current_settings();
}

bool dpls_phy6252_storage_set_password(void *context, const uint8_t salt[16],
                                       const uint8_t verifier[32])
{
    (void)context;
    if (settings_state != DPLS_SETTINGS_VALID) return false;
    memcpy(settings.salt, salt, DPLS_AUTH_SALT_SIZE);
    memcpy(settings.verifier, verifier, DPLS_AUTH_PROOF_SIZE);
    return persist_current_settings();
}

bool dpls_phy6252_storage_copy_verifier(uint8_t out[DPLS_AUTH_PROOF_SIZE])
{
    if (settings_state != DPLS_SETTINGS_VALID) {
        memset(out, 0, DPLS_AUTH_PROOF_SIZE);
        return false;
    }
    memcpy(out, settings.verifier, DPLS_AUTH_PROOF_SIZE);
    return true;
}

bool dpls_phy6252_storage_auth_lock_read(void *context)
{
    dpls_auth_lock_t record;
    (void)context;
    if (osal_snv_read(DPLS_AUTH_LOCK_SNV_ID, sizeof(record), &record) != SUCCESS) return false;
    if (record.magic != DPLS_AUTH_LOCK_MAGIC ||
        record.crc != dpls_crc16((const uint8_t *)&record, offsetof(dpls_auth_lock_t, crc)))
        return false;
    return record.locked != 0u;
}

bool dpls_phy6252_storage_auth_lock_write(void *context, bool locked)
{
    dpls_auth_lock_t current;
    dpls_auth_lock_t record;
    bool current_valid;
    (void)context;
    current_valid = osal_snv_read(DPLS_AUTH_LOCK_SNV_ID, sizeof(current), &current) == SUCCESS &&
                    current.magic == DPLS_AUTH_LOCK_MAGIC &&
                    current.crc == dpls_crc16((const uint8_t *)&current, offsetof(dpls_auth_lock_t, crc));
    if (current_valid && ((current.locked != 0u) == locked)) return true;
    if (!current_valid && !locked) return true;
    record.magic = DPLS_AUTH_LOCK_MAGIC;
    record.locked = locked ? 1u : 0u;
    record.reserved = 0u;
    record.crc = dpls_crc16((const uint8_t *)&record, offsetof(dpls_auth_lock_t, crc));
    return snv_write_bounded(DPLS_AUTH_LOCK_SNV_ID, sizeof(record), &record) == SUCCESS;
}

bool dpls_phy6252_storage_events_init(void *context, uint16_t *count, uint32_t *next_sequence)
{
    uint32_t max_sequence;
    (void)context;
    journal_cached_block = 0xffu;
    max_sequence = journal_latest_sequence();
    if (max_sequence == 0u || max_sequence == UINT32_MAX) {
        *count = 0u;
        *next_sequence = 1u;
        return true;
    }
    *count = journal_contiguous_count(max_sequence);
    *next_sequence = max_sequence + 1u;
    return true;
}

bool dpls_phy6252_storage_event_append(void *context, const dpls_event_t *event)
{
    (void)context;
    if (!event || event->sequence == 0u || pending_event_count >= DPLS_PENDING_EVENT_CAPACITY)
        return false;
    pending_events[pending_event_count++] = *event;
    return true;
}

bool dpls_phy6252_storage_event_read(void *context, uint32_t sequence, dpls_event_t *event)
{
    uint16_t slot;
    uint8_t block_index, record_index, i;
    uint8_t *block;
    (void)context;
    if (!event || sequence == 0u) return false;
    for (i = pending_event_count; i > 0u; --i) {
        if (pending_events[i - 1u].sequence == sequence) {
            *event = pending_events[i - 1u];
            return true;
        }
    }
    slot = (uint16_t)((sequence - 1u) % DPLS_EVENT_CAPACITY);
    block_index = (uint8_t)(slot / DPLS_JOURNAL_EVENTS_PER_BLOCK);
    record_index = (uint8_t)(slot % DPLS_JOURNAL_EVENTS_PER_BLOCK);
    block = journal_load_block(block_index);
    return journal_decode_record(block + record_index * DPLS_JOURNAL_RECORD_SIZE, event) &&
           event->sequence == sequence;
}

bool dpls_phy6252_storage_has_pending_journal(void)
{
    return pending_event_count != 0u;
}

bool dpls_phy6252_storage_service_journal(void)
{
    uint8_t block_index;
    uint8_t applied = 0u;
    uint8_t i;
    if (link_active || pending_event_count == 0u) return pending_event_count != 0u;

    block_index = (uint8_t)(((pending_events[0].sequence - 1u) % DPLS_EVENT_CAPACITY) /
                            DPLS_JOURNAL_EVENTS_PER_BLOCK);
    memset(journal_service_block, 0, sizeof(journal_service_block));
    (void)osal_snv_read((osalSnvId_t)(DPLS_JOURNAL_FIRST_SNV_ID + block_index),
                        (osalSnvLen_t)DPLS_JOURNAL_BLOCK_SIZE, journal_service_block);

    for (i = 0u; i < pending_event_count; ++i) {
        uint16_t slot = (uint16_t)((pending_events[i].sequence - 1u) % DPLS_EVENT_CAPACITY);
        uint8_t event_block = (uint8_t)(slot / DPLS_JOURNAL_EVENTS_PER_BLOCK);
        uint8_t record_index;
        if (event_block != block_index) break;
        record_index = (uint8_t)(slot % DPLS_JOURNAL_EVENTS_PER_BLOCK);
        journal_encode_record(journal_service_block + record_index * DPLS_JOURNAL_RECORD_SIZE,
                              &pending_events[i]);
        ++applied;
    }

    if (applied == 0u) return false;
    if (snv_write_bounded((osalSnvId_t)(DPLS_JOURNAL_FIRST_SNV_ID + block_index),
                          (osalSnvLen_t)DPLS_JOURNAL_BLOCK_SIZE,
                          journal_service_block) != SUCCESS) {
        journal_cached_block = 0xffu;
        return false;
    }

    journal_cached_block = block_index;
    memcpy(journal_block_cache, journal_service_block, sizeof(journal_block_cache));
    memmove(pending_events, pending_events + applied,
            (size_t)(pending_event_count - applied) * sizeof(pending_events[0]));
    pending_event_count = (uint8_t)(pending_event_count - applied);
    return pending_event_count != 0u;
}

void dpls_phy6252_storage_load_calibration(dpls_calib_t *line, dpls_calib_t *vcap,
                                           bool *line_from_nv)
{
    dpls_calib_nv_t nv;
    dpls_calib_default(line);
    dpls_calib_default(vcap);
    vcap->gain_milli = DPLS_VCAP_NOMINAL_GAIN_MILLI;
    *line_from_nv = false;
    if (osal_snv_read(DPLS_CALIB_SNV_ID, sizeof(nv), &nv) == SUCCESS &&
        nv.magic == DPLS_CALIB_MAGIC &&
        nv.crc == dpls_crc16((const uint8_t *)&nv, offsetof(dpls_calib_nv_t, crc))) {
        dpls_calib_t stored_line = {nv.line_gain_milli, nv.line_offset_mv};
        dpls_calib_t stored_vcap = {nv.vcap_gain_milli, nv.vcap_offset_mv};
        if (dpls_calib_valid(&stored_line)) {
            *line = stored_line;
            *line_from_nv = true;
        }
        if (dpls_calib_valid(&stored_vcap)) *vcap = stored_vcap;
    }
}

bool dpls_phy6252_storage_clear_settings(void)
{
    uint8_t marker = DPLS_SETTINGS_EMPTY_MARKER;
    memset(&settings, 0, sizeof(settings));
    if (snv_write_bounded(DPLS_SETTINGS_SNV_ID, sizeof(settings), &settings) != SUCCESS ||
        snv_write_bounded(DPLS_SETTINGS_STATE_SNV_ID, sizeof(marker), &marker) != SUCCESS ||
        !dpls_phy6252_storage_auth_lock_write(NULL, false)) {
        settings_state = DPLS_SETTINGS_CORRUPT;
        return false;
    }
    settings_state = DPLS_SETTINGS_EMPTY;
    return true;
}
