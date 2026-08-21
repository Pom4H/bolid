#include "dpls_phy6252_storage.h"

#include "dpls_durable_settings.h"
#include "dpls_phy6252_supervisor.h"
#include "osal_snv.h"
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define DPLS_SETTINGS_SLOT_A_SNV_ID 0x85u
#define DPLS_SETTINGS_SLOT_B_SNV_ID 0x86u
#define DPLS_CALIB_SNV_ID 0x83u
#define DPLS_AUTH_LOCK_SNV_ID 0x84u
#define DPLS_AUTH_LOCK_MAGIC 0x4b434c44u /* "DLCK" */
#define DPLS_JOURNAL_FIRST_SNV_ID 0x90u
#define DPLS_JOURNAL_EVENTS_PER_BLOCK 10u
#define DPLS_JOURNAL_RECORD_SIZE 12u
#define DPLS_JOURNAL_BLOCK_COUNT (DPLS_EVENT_CAPACITY / DPLS_JOURNAL_EVENTS_PER_BLOCK)
#define DPLS_JOURNAL_BLOCK_SIZE (DPLS_JOURNAL_EVENTS_PER_BLOCK * DPLS_JOURNAL_RECORD_SIZE)
#define DPLS_CALIB_MAGIC 0x434c5044u
#define DPLS_VCAP_NOMINAL_GAIN_MILLI 2000u

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

static dpls_durable_settings_t settings;
static dpls_settings_state_t settings_state;
static uint8_t selected_settings_slot;
static bool settings_dirty;
static bool auth_locked;
static bool auth_lock_dirty;
static bool link_active;

/* Весь журнал помещается в RAM: во время BLE нет ни flash read, ни flash write.
 * 200 × 12 = 2400 bytes. Это осознанная плата за детерминированность radio path. */
static uint8_t journal_records[DPLS_EVENT_CAPACITY][DPLS_JOURNAL_RECORD_SIZE];
static uint32_t journal_dirty_mask;

#if DPLS_EVENT_CAPACITY != 200u
#error "PHY6252 journal layout is defined for exactly 200 events"
#endif

#if DPLS_JOURNAL_BLOCK_COUNT > 32u
#error "journal dirty mask supports at most 32 blocks"
#endif

static uint8_t snv_write_offline(osalSnvId_t id, osalSnvLen_t len, void *data)
{
    uint8_t rc;
    if (link_active) return 0xffu;
    dpls_phy6252_supervisor_blocking_io_begin();
    rc = osal_snv_write(id, len, data);
    dpls_phy6252_supervisor_blocking_io_end();
    return rc;
}

static uint32_t rd32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void wr32(uint8_t *p, uint32_t value)
{
    p[0] = (uint8_t)value;
    p[1] = (uint8_t)(value >> 8);
    p[2] = (uint8_t)(value >> 16);
    p[3] = (uint8_t)(value >> 24);
}

static bool journal_decode_record(const uint8_t record[DPLS_JOURNAL_RECORD_SIZE],
                                  dpls_event_t *event)
{
    uint16_t stored_crc;
    if (!event) return false;
    stored_crc = (uint16_t)record[10] | ((uint16_t)record[11] << 8);
    if (stored_crc != dpls_crc16(record, 10u)) return false;
    event->sequence = rd32(record);
    event->timestamp_seconds = rd32(record + 4);
    event->event_type = record[8];
    event->parameter = record[9];
    return event->sequence != 0u && event->event_type >= 1u && event->event_type <= 14u;
}

static void journal_encode_record(uint8_t record[DPLS_JOURNAL_RECORD_SIZE],
                                  const dpls_event_t *event)
{
    uint16_t crc;
    wr32(record, event->sequence);
    wr32(record + 4, event->timestamp_seconds);
    record[8] = event->event_type;
    record[9] = event->parameter;
    crc = dpls_crc16(record, 10u);
    record[10] = (uint8_t)crc;
    record[11] = (uint8_t)(crc >> 8);
}

static void load_journal(void)
{
    uint8_t block;
    memset(journal_records, 0, sizeof(journal_records));
    journal_dirty_mask = 0u;

    for (block = 0u; block < DPLS_JOURNAL_BLOCK_COUNT; ++block) {
        uint8_t *dst = &journal_records[(uint16_t)block * DPLS_JOURNAL_EVENTS_PER_BLOCK][0];
        (void)osal_snv_read((osalSnvId_t)(DPLS_JOURNAL_FIRST_SNV_ID + block),
                            (osalSnvLen_t)DPLS_JOURNAL_BLOCK_SIZE, dst);
        dpls_phy6252_supervisor_checkpoint();
    }
}

static uint32_t journal_latest_sequence(void)
{
    dpls_event_t event;
    uint32_t max_sequence = 0u;
    uint16_t slot;

    for (slot = 0u; slot < DPLS_EVENT_CAPACITY; ++slot) {
        if (journal_decode_record(journal_records[slot], &event) &&
            event.sequence > max_sequence)
            max_sequence = event.sequence;
    }
    return max_sequence;
}

static uint16_t journal_contiguous_count(uint32_t max_sequence)
{
    dpls_event_t event;
    uint16_t count = 0u;

    while (count < DPLS_EVENT_CAPACITY) {
        uint32_t expected = max_sequence - count;
        uint16_t slot;
        if (expected == 0u) break;
        slot = (uint16_t)((expected - 1u) % DPLS_EVENT_CAPACITY);
        if (!journal_decode_record(journal_records[slot], &event) ||
            event.sequence != expected)
            break;
        ++count;
    }
    return count;
}

static void load_settings(void)
{
    uint8_t raw_a[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t raw_b[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    bool read_a;
    bool read_b;

    memset(raw_a, 0xff, sizeof(raw_a));
    memset(raw_b, 0xff, sizeof(raw_b));
    read_a = osal_snv_read(DPLS_SETTINGS_SLOT_A_SNV_ID, sizeof(raw_a), raw_a) == SUCCESS;
    read_b = osal_snv_read(DPLS_SETTINGS_SLOT_B_SNV_ID, sizeof(raw_b), raw_b) == SUCCESS;

    memset(&settings, 0, sizeof(settings));
    selected_settings_slot = 0xffu;
    settings_dirty = false;

    if (dpls_durable_settings_select(raw_a, raw_b, &settings, &selected_settings_slot)) {
        settings_state = settings.state == DPLS_DURABLE_SETTINGS_VALID
                             ? DPLS_SETTINGS_VALID
                             : DPLS_SETTINGS_EMPTY;
        return;
    }

    settings_state = (read_a || read_b) ? DPLS_SETTINGS_CORRUPT : DPLS_SETTINGS_EMPTY;
}

static void stage_settings_generation(void)
{
    settings.generation = dpls_durable_settings_next_generation(settings.generation);
    settings_dirty = true;
}

static bool commit_settings(void)
{
    uint8_t raw[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    uint8_t verify_raw[DPLS_DURABLE_SETTINGS_RECORD_SIZE];
    dpls_durable_settings_t verify;
    uint8_t target_slot = selected_settings_slot == 0u ? 1u : 0u;
    osalSnvId_t id = target_slot == 0u ? DPLS_SETTINGS_SLOT_A_SNV_ID
                                       : DPLS_SETTINGS_SLOT_B_SNV_ID;

    dpls_durable_settings_encode(raw, &settings);
    if (snv_write_offline(id, sizeof(raw), raw) != SUCCESS) return false;

    memset(verify_raw, 0xff, sizeof(verify_raw));
    if (osal_snv_read(id, sizeof(verify_raw), verify_raw) != SUCCESS ||
        !dpls_durable_settings_decode(verify_raw, &verify) ||
        verify.generation != settings.generation || verify.state != settings.state)
        return false;

    selected_settings_slot = target_slot;
    settings_dirty = false;
    return true;
}

static void load_auth_lock(void)
{
    dpls_auth_lock_t record;
    auth_locked = false;
    auth_lock_dirty = false;
    if (osal_snv_read(DPLS_AUTH_LOCK_SNV_ID, sizeof(record), &record) != SUCCESS) return;
    if (record.magic != DPLS_AUTH_LOCK_MAGIC ||
        record.crc != dpls_crc16((const uint8_t *)&record,
                                 offsetof(dpls_auth_lock_t, crc)))
        return;
    auth_locked = record.locked != 0u;
}

static bool commit_auth_lock(void)
{
    dpls_auth_lock_t record;
    record.magic = DPLS_AUTH_LOCK_MAGIC;
    record.locked = auth_locked ? 1u : 0u;
    record.reserved = 0u;
    record.crc = dpls_crc16((const uint8_t *)&record,
                            offsetof(dpls_auth_lock_t, crc));
    if (snv_write_offline(DPLS_AUTH_LOCK_SNV_ID, sizeof(record), &record) != SUCCESS)
        return false;
    auth_lock_dirty = false;
    return true;
}

static bool commit_one_journal_block(void)
{
    uint8_t block;
    for (block = 0u; block < DPLS_JOURNAL_BLOCK_COUNT; ++block) {
        uint32_t bit = (uint32_t)1u << block;
        uint8_t *src;
        if ((journal_dirty_mask & bit) == 0u) continue;
        src = &journal_records[(uint16_t)block * DPLS_JOURNAL_EVENTS_PER_BLOCK][0];
        if (snv_write_offline((osalSnvId_t)(DPLS_JOURNAL_FIRST_SNV_ID + block),
                              (osalSnvLen_t)DPLS_JOURNAL_BLOCK_SIZE, src) != SUCCESS)
            return false;
        journal_dirty_mask &= ~bit;
        return true;
    }
    return false;
}

void dpls_phy6252_storage_init(void)
{
    link_active = false;
    load_settings();
    load_auth_lock();
    load_journal();
}

void dpls_phy6252_storage_set_link_active(bool active)
{
    link_active = active;
}

bool dpls_phy6252_storage_work_pending(void)
{
    return settings_dirty || auth_lock_dirty || journal_dirty_mask != 0u;
}

bool dpls_phy6252_storage_disconnect_requested(void)
{
    return link_active && (settings_dirty || auth_lock_dirty);
}

bool dpls_phy6252_storage_process_one(void)
{
    if (link_active) return false;
    if (settings_dirty) return commit_settings();
    if (auth_lock_dirty) return commit_auth_lock();
    return commit_one_journal_block();
}

dpls_settings_state_t dpls_phy6252_storage_settings_state(void *context)
{
    (void)context;
    return settings_state;
}

void dpls_phy6252_storage_settings_salt(void *context, uint8_t out[DPLS_AUTH_SALT_SIZE])
{
    (void)context;
    if (settings_state == DPLS_SETTINGS_VALID)
        memcpy(out, settings.salt, DPLS_AUTH_SALT_SIZE);
    else
        memset(out, 0, DPLS_AUTH_SALT_SIZE);
}

bool dpls_phy6252_storage_write_settings(void *context, const char *name,
                                         const uint8_t salt[16], const uint8_t verifier[32])
{
    size_t length;
    (void)context;
    if (!name || !salt || !verifier) return false;
    length = strlen(name);
    if (length == 0u || length >= DPLS_DURABLE_SETTINGS_NAME_SIZE) return false;

    memset(settings.name, 0, sizeof(settings.name));
    memcpy(settings.name, name, length);
    memcpy(settings.salt, salt, DPLS_AUTH_SALT_SIZE);
    memcpy(settings.verifier, verifier, DPLS_AUTH_PROOF_SIZE);
    settings.state = DPLS_DURABLE_SETTINGS_VALID;
    settings_state = DPLS_SETTINGS_VALID;
    stage_settings_generation();
    return true;
}

void dpls_phy6252_storage_settings_name(void *context, char out[DPLS_NAME_MAX + 1u])
{
    (void)context;
    if (settings_state != DPLS_SETTINGS_VALID) {
        out[0] = '\0';
        return;
    }
    memcpy(out, settings.name, DPLS_NAME_MAX);
    out[DPLS_NAME_MAX] = '\0';
}

bool dpls_phy6252_storage_set_name(void *context, const char *name)
{
    size_t length;
    (void)context;
    if (settings_state != DPLS_SETTINGS_VALID || !name) return false;
    length = strlen(name);
    if (length == 0u || length >= DPLS_DURABLE_SETTINGS_NAME_SIZE) return false;

    memset(settings.name, 0, sizeof(settings.name));
    memcpy(settings.name, name, length);
    stage_settings_generation();
    return true;
}

bool dpls_phy6252_storage_set_password(void *context, const uint8_t salt[16],
                                       const uint8_t verifier[32])
{
    (void)context;
    if (settings_state != DPLS_SETTINGS_VALID || !salt || !verifier) return false;
    memcpy(settings.salt, salt, DPLS_AUTH_SALT_SIZE);
    memcpy(settings.verifier, verifier, DPLS_AUTH_PROOF_SIZE);
    stage_settings_generation();
    return true;
}

bool dpls_phy6252_storage_copy_verifier(uint8_t out[DPLS_AUTH_PROOF_SIZE])
{
    if (!out || settings_state != DPLS_SETTINGS_VALID) {
        if (out) memset(out, 0, DPLS_AUTH_PROOF_SIZE);
        return false;
    }
    memcpy(out, settings.verifier, DPLS_AUTH_PROOF_SIZE);
    return true;
}

bool dpls_phy6252_storage_auth_lock_read(void *context)
{
    (void)context;
    return auth_locked;
}

bool dpls_phy6252_storage_auth_lock_write(void *context, bool locked)
{
    (void)context;
    if (auth_locked == locked) return true;
    auth_locked = locked;
    auth_lock_dirty = true;
    return true;
}

bool dpls_phy6252_storage_events_init(void *context, uint16_t *count,
                                      uint32_t *next_sequence)
{
    uint32_t max_sequence;
    (void)context;
    if (!count || !next_sequence) return false;

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
    uint16_t slot;
    uint8_t block;
    (void)context;
    if (!event || event->sequence == 0u) return false;

    slot = (uint16_t)((event->sequence - 1u) % DPLS_EVENT_CAPACITY);
    block = (uint8_t)(slot / DPLS_JOURNAL_EVENTS_PER_BLOCK);
    journal_encode_record(journal_records[slot], event);
    journal_dirty_mask |= (uint32_t)1u << block;
    return true;
}

bool dpls_phy6252_storage_event_read(void *context, uint32_t sequence, dpls_event_t *event)
{
    uint16_t slot;
    (void)context;
    if (!event || sequence == 0u) return false;
    slot = (uint16_t)((sequence - 1u) % DPLS_EVENT_CAPACITY);
    return journal_decode_record(journal_records[slot], event) &&
           event->sequence == sequence;
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
        nv.crc == dpls_crc16((const uint8_t *)&nv,
                             offsetof(dpls_calib_nv_t, crc))) {
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
    memset(settings.name, 0, sizeof(settings.name));
    memset(settings.salt, 0, sizeof(settings.salt));
    memset(settings.verifier, 0, sizeof(settings.verifier));
    settings.state = DPLS_DURABLE_SETTINGS_EMPTY;
    settings_state = DPLS_SETTINGS_EMPTY;
    stage_settings_generation();

    if (auth_locked) {
        auth_locked = false;
        auth_lock_dirty = true;
    }
    return true;
}
