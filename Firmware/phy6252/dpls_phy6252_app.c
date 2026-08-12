#include "dpls_phy6252_app.h"

#include "dpls_ble_identity.h"
#include "dpls_board.h"
#include "dpls_gatt_service.h"
#include "dpls_led.h"
#include "dpls_phy6252_adc.h"
#include "dpls_phy6252_hw.h"
#include "dpls_server.h"
#include "OSAL.h"
#include "OSAL_Timers.h"
#include "gpio.h"
#include "linkdb.h"
#include "ll_enc.h"
#include "osal_snv.h"
#include "watchdog.h"
#include "gapbondmgr.h"
#include "peripheral.h"
#include <core_cm0.h>
#include <tinycrypt/hmac.h>
#include <stddef.h>
#include <string.h>

#define DPLS_SETTINGS_MAGIC 0x534C5044u
#define DPLS_AUTH_LOCK_MAGIC 0x4B434C44u /* "DLCK" */
#define DPLS_SETTINGS_SNV_ID 0x80u
#define DPLS_SETTINGS_STATE_SNV_ID 0x81u
#define DPLS_AUTH_LOCK_SNV_ID 0x84u
#define DPLS_JOURNAL_FIRST_SNV_ID 0x90u
#define DPLS_JOURNAL_EVENTS_PER_BLOCK 10u
#define DPLS_JOURNAL_RECORD_SIZE 12u
#define DPLS_JOURNAL_BLOCK_COUNT (DPLS_EVENT_CAPACITY / DPLS_JOURNAL_EVENTS_PER_BLOCK)
#define DPLS_JOURNAL_BLOCK_SIZE (DPLS_JOURNAL_EVENTS_PER_BLOCK * DPLS_JOURNAL_RECORD_SIZE)
#define DPLS_NAME_SIZE 32u
#define DPLS_HW_REVISION 2u /* +1, +2, +T and reserve are independent ADC inputs */
#define DPLS_SETTINGS_EMPTY_MARKER 0x45u
#define DPLS_SETTINGS_VALID_MARKER 0x56u
#define DPLS_FACTORY_RESET_PIN DPLS_PIN_FACTORY_RESET
#define DPLS_FACTORY_RESET_HOLD_MS 5000u

/* Power-source and real-short interpretation is domain logic, not ADC-driver
 * logic. The ADC adapter only supplies fresh calibrated voltages. */
#define DPLS_LINE_PRESENT_MV 4000u
#define DPLS_LINE_ABSENT_MV 3000u
#define DPLS_RESERVE_LOW_MV 3700u
#define DPLS_RESERVE_OK_MV 4000u
#define DPLS_AUTOISO_TRIP_MV 3000u
#define DPLS_AUTOISO_CLEAR_MV 4500u

#define DPLS_BOND_DESYNC_LIMIT 3u
#define DPLS_BOND_DESYNC_WINDOW_MS 120000u
#define DPLS_LINK_ENCRYPT_TIMEOUT_MS 15000u

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

static dpls_server_t server;
static dpls_settings_t settings;
static dpls_led_t status_led;
static bool identify_led_active;
static uint16 connection_handle = INVALID_CONNHANDLE;
static uint8 task_id;
static dpls_settings_state_t settings_state = DPLS_SETTINGS_EMPTY;
static bool factory_reset_armed;
static uint32_t factory_reset_started_ms;
static uint32_t connected_at_ms;
static bool connection_had_encryption;
static uint8_t pre_auth_disconnect_count;
static uint32_t pre_auth_disconnect_window_ms;

#define DPLS_RX_QUEUE_DEPTH 6u
#define DPLS_RX_SLOT_SIZE 96u
typedef struct { uint8 data[DPLS_RX_SLOT_SIZE]; uint16 length; } dpls_rx_slot_t;
static dpls_rx_slot_t rx_queue[DPLS_RX_QUEUE_DEPTH];
static uint8 rx_head, rx_tail, rx_count;

#define DPLS_TX_QUEUE_DEPTH 4u
#define DPLS_TX_SLOT_SIZE 168u
typedef struct { uint16 length; uint8 data[DPLS_TX_SLOT_SIZE]; } dpls_tx_slot_t;
static dpls_tx_slot_t tx_queue[DPLS_TX_QUEUE_DEPTH];
static uint8 tx_head, tx_tail, tx_count;
static bool tx_in_flight;
#define DPLS_TX_CONFIRM_TIMEOUT_MS 2000u
static uint32_t tx_in_flight_since_ms;

static uint8_t journal_block_cache[DPLS_JOURNAL_BLOCK_SIZE];
static uint8_t journal_cached_block = 0xffu;

static dpls_power_t power_state = DPLS_POWER_LINE;
static bool reserve_low_state;
static bool auto_isolation_active;
static bool line_established;

static uint32_t now_ms(void) { return (uint32_t)osal_GetSystemClock(); }

#if DPLS_EVENT_CAPACITY != 200u
#error "PHY6252 journal layout is defined for exactly 200 events"
#endif

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

static uint8_t *journal_load_block(uint8_t block_index)
{
    if (journal_cached_block == block_index) return journal_block_cache;
    memset(journal_block_cache, 0, sizeof(journal_block_cache));
    (void)osal_snv_read((osalSnvId_t)(DPLS_JOURNAL_FIRST_SNV_ID + block_index),
                        (osalSnvLen_t)DPLS_JOURNAL_BLOCK_SIZE, journal_block_cache);
    journal_cached_block = block_index;
    return journal_block_cache;
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

static bool journal_storage_init(void *context, uint16_t *count, uint32_t *next_sequence)
{
    uint8_t *block;
    uint8_t present[(DPLS_EVENT_CAPACITY + 7u) / 8u];
    dpls_event_t event;
    uint32_t max_sequence = 0;
    uint16_t block_index, record_index, suffix_count = 0;
    (void)context;
    memset(present, 0, sizeof(present));
    journal_cached_block = 0xffu;

    for (block_index = 0; block_index < DPLS_JOURNAL_BLOCK_COUNT; ++block_index) {
        hal_watchdog_feed();
        block = journal_load_block((uint8_t)block_index);
        for (record_index = 0; record_index < DPLS_JOURNAL_EVENTS_PER_BLOCK; ++record_index) {
            uint16_t slot = (uint16_t)(block_index * DPLS_JOURNAL_EVENTS_PER_BLOCK + record_index);
            if (journal_decode_record(block + record_index * DPLS_JOURNAL_RECORD_SIZE, &event) &&
                (uint16_t)((event.sequence - 1u) % DPLS_EVENT_CAPACITY) == slot &&
                event.sequence > max_sequence) max_sequence = event.sequence;
        }
    }
    if (max_sequence == 0u || max_sequence == UINT32_MAX) {
        *count = 0;
        *next_sequence = 1;
        return true;
    }

    for (block_index = 0; block_index < DPLS_JOURNAL_BLOCK_COUNT; ++block_index) {
        hal_watchdog_feed();
        block = journal_load_block((uint8_t)block_index);
        for (record_index = 0; record_index < DPLS_JOURNAL_EVENTS_PER_BLOCK; ++record_index) {
            uint16_t slot = (uint16_t)(block_index * DPLS_JOURNAL_EVENTS_PER_BLOCK + record_index);
            uint32_t age;
            if (!journal_decode_record(block + record_index * DPLS_JOURNAL_RECORD_SIZE, &event) ||
                (uint16_t)((event.sequence - 1u) % DPLS_EVENT_CAPACITY) != slot ||
                event.sequence > max_sequence) continue;
            age = max_sequence - event.sequence;
            if (age < DPLS_EVENT_CAPACITY)
                present[age / 8u] |= (uint8_t)(1u << (age % 8u));
        }
    }
    while (suffix_count < DPLS_EVENT_CAPACITY &&
           (present[suffix_count / 8u] & (uint8_t)(1u << (suffix_count % 8u)))) ++suffix_count;
    *count = suffix_count;
    *next_sequence = max_sequence + 1u;
    return true;
}

static bool journal_storage_append(void *context, const dpls_event_t *event)
{
    uint8_t *block;
    uint16_t slot;
    uint8_t block_index, record_index;
    (void)context;
    if (!event || event->sequence == 0u) return false;
    slot = (uint16_t)((event->sequence - 1u) % DPLS_EVENT_CAPACITY);
    block_index = (uint8_t)(slot / DPLS_JOURNAL_EVENTS_PER_BLOCK);
    record_index = (uint8_t)(slot % DPLS_JOURNAL_EVENTS_PER_BLOCK);
    block = journal_load_block(block_index);
    journal_encode_record(block + record_index * DPLS_JOURNAL_RECORD_SIZE, event);
    return osal_snv_write((osalSnvId_t)(DPLS_JOURNAL_FIRST_SNV_ID + block_index),
                          (osalSnvLen_t)DPLS_JOURNAL_BLOCK_SIZE, block) == SUCCESS;
}

static bool journal_storage_read(void *context, uint32_t sequence, dpls_event_t *event)
{
    uint8_t *block;
    uint16_t slot;
    uint8_t block_index, record_index;
    (void)context;
    if (!event || sequence == 0u) return false;
    slot = (uint16_t)((sequence - 1u) % DPLS_EVENT_CAPACITY);
    block_index = (uint8_t)(slot / DPLS_JOURNAL_EVENTS_PER_BLOCK);
    record_index = (uint8_t)(slot % DPLS_JOURNAL_EVENTS_PER_BLOCK);
    block = journal_load_block(block_index);
    return journal_decode_record(block + record_index * DPLS_JOURNAL_RECORD_SIZE, event) &&
           event->sequence == sequence;
}

static bool link_encrypted(void *context)
{
    (void)context;
    return connection_handle != INVALID_CONNHANDLE && linkDB_Encrypted(connection_handle);
}

static void safe_normal(void *context)
{
    (void)context;
    dpls_phy6252_hw_safe_normal();
}

static bool apply_mode(void *context, dpls_mode_t mode)
{
    (void)context;
    return dpls_phy6252_hw_apply_mode(mode);
}

static void status_led_output(void *context, bool on)
{
    (void)context;
    dpls_phy6252_hw_identify_led(on);
}

/* Derive domain state only from fresh measurements. A stale reserve reading is
 * treated as low by reserve_low() below, which is the fail-safe direction for a
 * control device: stale telemetry can never leave a test mode energized. */
static void update_power_state(uint32_t now)
{
    uint8_t validity = dpls_phy6252_adc_validity(now);

    if (validity & DPLS_STATE_PORT_1_VALID) {
        uint16_t line = dpls_phy6252_adc_port1_mv();
        if (power_state == DPLS_POWER_LINE && line < DPLS_LINE_ABSENT_MV)
            power_state = DPLS_POWER_RESERVE;
        else if (power_state == DPLS_POWER_RESERVE && line > DPLS_LINE_PRESENT_MV)
            power_state = DPLS_POWER_LINE;

        if (line > DPLS_LINE_PRESENT_MV) line_established = true;
        if (line_established && dpls_phy6252_hw_mode() == DPLS_MODE_NORMAL) {
            if (!auto_isolation_active && line < DPLS_AUTOISO_TRIP_MV) auto_isolation_active = true;
            else if (auto_isolation_active && line > DPLS_AUTOISO_CLEAR_MV) auto_isolation_active = false;
        }
    } else {
        /* Hardware performs the actual fast isolation. Do not keep exporting a
         * software-derived real-short flag after its measurement has gone stale. */
        auto_isolation_active = false;
    }

    if (validity & DPLS_STATE_RESERVE_VOLTAGE_VALID) {
        uint16_t reserve = dpls_phy6252_adc_reserve_mv();
        if (!reserve_low_state && reserve < DPLS_RESERVE_LOW_MV) reserve_low_state = true;
        else if (reserve_low_state && reserve > DPLS_RESERVE_OK_MV) reserve_low_state = false;
    }
}

static uint16_t voltage_mv(void *context)
{
    (void)context;
    return dpls_phy6252_adc_port1_mv();
}

static uint16_t port1_voltage_mv(void *context)
{
    (void)context;
    return dpls_phy6252_adc_port1_mv();
}

static uint16_t port2_voltage_mv(void *context)
{
    (void)context;
    return dpls_phy6252_adc_port2_mv();
}

static uint16_t port_t_voltage_mv(void *context)
{
    (void)context;
    return dpls_phy6252_adc_port_t_mv();
}

static uint16_t reserve_voltage_mv(void *context)
{
    (void)context;
    return dpls_phy6252_adc_reserve_mv();
}

static dpls_power_t power_source(void *context)
{
    (void)context;
    return power_state;
}

static bool reserve_low(void *context)
{
    uint8_t validity;
    (void)context;
    validity = dpls_phy6252_adc_validity(now_ms());
    if (!(validity & DPLS_STATE_RESERVE_VOLTAGE_VALID))
        return true;
    return reserve_low_state;
}

static bool real_short_active(void *context)
{
    (void)context;
    if (!(dpls_phy6252_adc_validity(now_ms()) & DPLS_STATE_PORT_1_VALID))
        return false;
    return auto_isolation_active;
}

static uint8_t measurement_validity(void *context)
{
    uint8_t flags;
    (void)context;
    flags = dpls_phy6252_adc_validity(now_ms());
    if (flags & DPLS_STATE_PORT_1_VALID)
        flags |= DPLS_STATE_POWER_VALID | DPLS_STATE_AUTOISO_VALID;
    if (dpls_phy6252_adc_fully_calibrated())
        flags |= DPLS_STATE_ADC_CALIBRATED;
    return flags;
}

static void identify_led(void *context, bool enabled)
{
    (void)context;
    identify_led_active = enabled;
}

static bool random_bytes(void *context, uint8_t *out, size_t length)
{
    uint8_t generated;
    size_t offset = 0;
    (void)context;
    while (offset < length) {
        uint8_t chunk = (uint8_t)((length - offset) > 16u ? 16u : (length - offset));
        generated = LL_ENC_GenerateTrueRandNum(out + offset, chunk);
        if (generated != SUCCESS) {
            memset(out, 0, length);
            safe_normal(NULL);
            return false;
        }
        offset += chunk;
    }
    return true;
}

static dpls_settings_state_t get_settings_state(void *context)
{
    (void)context;
    return settings_state;
}

static void settings_salt(void *context, uint8_t out[DPLS_AUTH_SALT_SIZE])
{
    (void)context;
    if (settings_state == DPLS_SETTINGS_VALID) memcpy(out, settings.salt, DPLS_AUTH_SALT_SIZE);
    else memset(out, 0, DPLS_AUTH_SALT_SIZE);
}

static bool persist_current_settings(void)
{
    dpls_settings_t verified;
    uint8 marker = DPLS_SETTINGS_VALID_MARKER;
    settings.magic = DPLS_SETTINGS_MAGIC;
    settings.crc = dpls_crc16((const uint8_t *)&settings, offsetof(dpls_settings_t, crc));
    if (osal_snv_write(DPLS_SETTINGS_SNV_ID, sizeof(settings), &settings) != SUCCESS ||
        osal_snv_read(DPLS_SETTINGS_SNV_ID, sizeof(verified), &verified) != SUCCESS ||
        verified.magic != DPLS_SETTINGS_MAGIC ||
        verified.crc != dpls_crc16((const uint8_t *)&verified, offsetof(dpls_settings_t, crc)) ||
        osal_snv_write(DPLS_SETTINGS_STATE_SNV_ID, sizeof(marker), &marker) != SUCCESS) {
        memset(&settings, 0, sizeof(settings));
        settings_state = DPLS_SETTINGS_CORRUPT;
        return false;
    }
    settings_state = DPLS_SETTINGS_VALID;
    return true;
}

static bool write_settings(void *context, const char *name, const uint8_t salt[16], const uint8_t verifier[32])
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

static void settings_name(void *context, char out[DPLS_NAME_MAX + 1u])
{
    (void)context;
    if (settings_state == DPLS_SETTINGS_VALID) {
        memcpy(out, settings.name, DPLS_NAME_MAX);
        out[DPLS_NAME_MAX] = '\0';
    } else {
        out[0] = '\0';
    }
}

static bool settings_set_name(void *context, const char *name)
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

static bool settings_set_password(void *context, const uint8_t salt[16], const uint8_t verifier[32])
{
    (void)context;
    if (settings_state != DPLS_SETTINGS_VALID) return false;
    memcpy(settings.salt, salt, DPLS_AUTH_SALT_SIZE);
    memcpy(settings.verifier, verifier, DPLS_AUTH_PROOF_SIZE);
    return persist_current_settings();
}

static void device_info(void *context, dpls_device_info_t *out)
{
    (void)context;
    out->device_id = dpls_ble_identity_device_id();
    out->fw_major = DPLS_FW_VERSION_MAJOR;
    out->fw_minor = DPLS_FW_VERSION_MINOR;
    out->fw_patch = DPLS_FW_VERSION_PATCH;
    out->hw_revision = DPLS_HW_REVISION;
    out->capabilities = DPLS_CAP_ADC_PRESENT | DPLS_CAP_MULTI_VOLTAGE_REPORT;
    if (dpls_phy6252_adc_fully_calibrated())
        out->capabilities |= DPLS_CAP_ADC_CALIBRATED;
    /* DPLS_CAP_HW_READBACK stays clear: commanded mode is not electrical readback. */
}

static bool auth_lock_read(void *context)
{
    dpls_auth_lock_t record;
    (void)context;
    if (osal_snv_read(DPLS_AUTH_LOCK_SNV_ID, sizeof(record), &record) != SUCCESS) return false;
    if (record.magic != DPLS_AUTH_LOCK_MAGIC ||
        record.crc != dpls_crc16((const uint8_t *)&record, offsetof(dpls_auth_lock_t, crc)))
        return false;
    return record.locked != 0u;
}

static bool auth_lock_write(void *context, bool locked)
{
    dpls_auth_lock_t record;
    (void)context;
    record.magic = DPLS_AUTH_LOCK_MAGIC;
    record.locked = locked ? 1u : 0u;
    record.reserved = 0u;
    record.crc = dpls_crc16((const uint8_t *)&record, offsetof(dpls_auth_lock_t, crc));
    return osal_snv_write(DPLS_AUTH_LOCK_SNV_ID, sizeof(record), &record) == SUCCESS;
}

static bool verify_proof(void *context, const uint8_t device_nonce[16], const uint8_t client_nonce[16],
                         uint32_t session_id, const uint8_t proof[32])
{
    struct tc_hmac_state_struct hmac;
    uint8_t signed_data[36];
    uint8_t expected[32];
    uint8_t difference = 0;
    uint8_t i;
    (void)context;
    if (settings_state != DPLS_SETTINGS_VALID) return false;
    memcpy(signed_data, device_nonce, 16);
    memcpy(signed_data + 16, client_nonce, 16);
    signed_data[32] = (uint8_t)session_id;
    signed_data[33] = (uint8_t)(session_id >> 8);
    signed_data[34] = (uint8_t)(session_id >> 16);
    signed_data[35] = (uint8_t)(session_id >> 24);
    if (!tc_hmac_set_key(&hmac, settings.verifier, sizeof(settings.verifier)) ||
        !tc_hmac_init(&hmac) || !tc_hmac_update(&hmac, signed_data, sizeof(signed_data)) ||
        !tc_hmac_final(expected, sizeof(expected), &hmac)) return false;
    for (i = 0; i < sizeof(expected); ++i) difference |= (uint8_t)(expected[i] ^ proof[i]);
    memset(expected, 0, sizeof(expected));
    memset(signed_data, 0, sizeof(signed_data));
    return difference == 0;
}

static void diagnostic_error(void *context, bool critical)
{
    (void)context;
    if (!critical) return;
    safe_normal(NULL);
    if (connection_handle != INVALID_CONNHANDLE)
        (void)GAPRole_TerminateConnection();
}

static void tx_pump(void)
{
    bStatus_t rc;
    if (tx_in_flight || tx_count == 0u || connection_handle == INVALID_CONNHANDLE) return;
    rc = dpls_gatt_send_indication(connection_handle, tx_queue[tx_head].data,
                                   tx_queue[tx_head].length, task_id);
    if (rc == SUCCESS) {
        tx_in_flight = true;
        tx_in_flight_since_ms = now_ms();
    } else if (rc == bleMemAllocError || rc == blePending) {
        /* transient: retry from the next task tick */
    } else {
        tx_head = (uint8)((tx_head + 1u) % DPLS_TX_QUEUE_DEPTH);
        if (tx_count) --tx_count;
        if (tx_count) osal_set_event(task_id, DPLS_PHY6252_TX_EVT);
    }
}

static bool tx_indicate(void *context, const uint8_t *frame, size_t length)
{
    (void)context;
    if (length > DPLS_TX_SLOT_SIZE) return false;
    if (tx_count >= DPLS_TX_QUEUE_DEPTH) {
        safe_normal(NULL);
        tx_head = tx_tail = tx_count = 0;
        tx_in_flight = false;
        if (connection_handle != INVALID_CONNHANDLE) (void)GAPRole_TerminateConnection();
        return false;
    }
    memcpy(tx_queue[tx_tail].data, frame, length);
    tx_queue[tx_tail].length = (uint16)length;
    tx_tail = (uint8)((tx_tail + 1u) % DPLS_TX_QUEUE_DEPTH);
    ++tx_count;
    osal_set_event(task_id, DPLS_PHY6252_TX_EVT);
    return true;
}

void dpls_phy6252_process_tx(void)
{
    tx_pump();
}

void dpls_phy6252_tx_confirmed(void)
{
    if (tx_in_flight) {
        tx_head = (uint8)((tx_head + 1u) % DPLS_TX_QUEUE_DEPTH);
        if (tx_count) --tx_count;
        tx_in_flight = false;
    }
    tx_pump();
}

static bool tx_notify(void *context, const uint8_t *frame, size_t length)
{
    (void)context;
    return dpls_gatt_send_notification(connection_handle, frame, (uint16)length, task_id);
}

static uint8 receive_frame(const uint8 *data, uint16 length)
{
    dpls_rx_slot_t *slot;
    if (length > DPLS_RX_SLOT_SIZE) return ATT_ERR_INVALID_VALUE_SIZE;
    if (rx_count >= DPLS_RX_QUEUE_DEPTH) return ATT_ERR_INSUFFICIENT_RESOURCES;
    slot = &rx_queue[rx_tail];
    memcpy(slot->data, data, length);
    slot->length = length;
    rx_tail = (uint8)((rx_tail + 1u) % DPLS_RX_QUEUE_DEPTH);
    ++rx_count;
    osal_set_event(task_id, DPLS_PHY6252_RX_EVT);
    return SUCCESS;
}

static void clear_settings_and_bonds(void)
{
    uint8 marker = DPLS_SETTINGS_EMPTY_MARKER;
    memset(&settings, 0, sizeof(settings));
    (void)osal_snv_write(DPLS_SETTINGS_SNV_ID, sizeof(settings), &settings);
    (void)osal_snv_write(DPLS_SETTINGS_STATE_SNV_ID, sizeof(marker), &marker);
    (void)auth_lock_write(NULL, false);
    settings_state = DPLS_SETTINGS_EMPTY;
    GAPBondMgr_SetParameter(GAPBOND_ERASE_ALLBONDS, 0, NULL);
    dpls_ble_identity_reset_bonding_keys();
    dpls_phy6252_hw_identify_led(true);
    NVIC_SystemReset();
}

static void disconnect_after_setup(void *context)
{
    (void)context;
    (void)GAPRole_TerminateConnection();
}

static void classify_settings(void)
{
    uint16_t expected_crc;
    uint8 marker = 0;
    uint8 state_read;
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

void dpls_phy6252_init(uint8 new_task_id)
{
    dpls_hal_t hal;
    task_id = new_task_id;
    connection_handle = INVALID_CONNHANDLE;
    rx_head = rx_tail = rx_count = 0;
    tx_head = tx_tail = tx_count = 0;
    tx_in_flight = false;
    identify_led_active = false;

    /* Target startup calls hw_init even earlier; this second call is intentionally
     * idempotent and makes the app safe if the integration order ever changes. */
    (void)dpls_phy6252_hw_init();
    (void)dpls_phy6252_adc_init(task_id, DPLS_PHY6252_ADC_EVT);

    dpls_phy6252_hw_safe_normal();
    dpls_phy6252_hw_identify_led(false);
    dpls_led_init(&status_led, status_led_output, NULL, now_ms());
    power_state = DPLS_POWER_LINE;
    reserve_low_state = false;
    auto_isolation_active = false;
    line_established = false;

    hal_gpio_pin_init(DPLS_FACTORY_RESET_PIN, IE);
    hal_gpio_pull_set(DPLS_FACTORY_RESET_PIN, GPIO_PULL_DOWN);
    classify_settings();
    factory_reset_armed = hal_gpio_read(DPLS_FACTORY_RESET_PIN);
    factory_reset_started_ms = now_ms();
    if (settings_state == DPLS_SETTINGS_EMPTY) {
        GAPBondMgr_SetParameter(GAPBOND_ERASE_ALLBONDS, 0, NULL);
        dpls_ble_identity_reset_bonding_keys();
    }

    memset(&hal, 0, sizeof(hal));
    hal.link_encrypted = link_encrypted;
    hal.hardware_apply_mode = apply_mode;
    hal.hardware_safe_normal = safe_normal;
    hal.voltage_mv = voltage_mv;
    hal.port1_voltage_mv = port1_voltage_mv;
    hal.port2_voltage_mv = port2_voltage_mv;
    hal.port_t_voltage_mv = port_t_voltage_mv;
    hal.reserve_voltage_mv = reserve_voltage_mv;
    hal.power_source = power_source;
    hal.reserve_low = reserve_low;
    hal.measurement_validity = measurement_validity;
    hal.real_short_active = real_short_active;
    hal.identify_led = identify_led;
    hal.random_bytes = random_bytes;
    hal.settings_state = get_settings_state;
    hal.settings_salt = settings_salt;
    hal.settings_write = write_settings;
    hal.settings_name = settings_name;
    hal.settings_set_name = settings_set_name;
    hal.settings_set_password = settings_set_password;
    hal.device_info = device_info;
    hal.verify_auth_proof = verify_proof;
    hal.auth_lock_read = auth_lock_read;
    hal.auth_lock_write = auth_lock_write;
    hal.event_storage_init = journal_storage_init;
    hal.event_storage_append = journal_storage_append;
    hal.event_storage_read = journal_storage_read;
    hal.tx_indicate = tx_indicate;
    hal.tx_notify = tx_notify;
    hal.diagnostic_error = diagnostic_error;
    hal.disconnect_after_setup = disconnect_after_setup;
    dpls_server_init(&server, &hal, now_ms());
    (void)dpls_gatt_add_service(receive_frame);
}

static void erase_stored_bonds(void)
{
    GAPBondMgr_SetParameter(GAPBOND_ERASE_ALLBONDS, 0, NULL);
}

static void erase_bonds_and_drop_link(void)
{
    erase_stored_bonds();
    if (connection_handle != INVALID_CONNHANDLE)
        (void)GAPRole_TerminateConnection();
}

static void note_pre_auth_disconnect(void)
{
    uint32_t now = now_ms();
    if (server.authenticated || !connection_had_encryption) return;
    if (pre_auth_disconnect_window_ms == 0u ||
        (uint32_t)(now - pre_auth_disconnect_window_ms) > DPLS_BOND_DESYNC_WINDOW_MS) {
        pre_auth_disconnect_count = 0;
        pre_auth_disconnect_window_ms = now;
    }
    if (++pre_auth_disconnect_count < DPLS_BOND_DESYNC_LIMIT) return;
    pre_auth_disconnect_count = 0;
    pre_auth_disconnect_window_ms = 0;
    erase_stored_bonds();
}

void dpls_phy6252_connected(uint16 conn_handle)
{
    connection_handle = conn_handle;
    connected_at_ms = now_ms();
    connection_had_encryption = false;

    /* A connected session without this lock is a known unsafe state on SDK
     * 3.1.2: ADC clock changes can race radio sleep/wake and freeze OSAL/GATT.
     * Unlike previous releases, failure is checked and the link is terminated
     * instead of continuing with a silently ineffective lock. */
    if (!dpls_phy6252_hw_connection_lock()) {
        safe_normal(NULL);
        connected_at_ms = 0u;
        (void)GAPRole_TerminateConnection();
        return;
    }
    dpls_server_connected(&server, now_ms());
}

void dpls_phy6252_disconnected(void)
{
    (void)dpls_phy6252_hw_connection_unlock();
    note_pre_auth_disconnect();
    dpls_server_disconnected(&server, now_ms());
    connection_handle = INVALID_CONNHANDLE;
    connected_at_ms = 0;
    connection_had_encryption = false;
    rx_head = rx_tail = rx_count = 0;
    tx_head = tx_tail = tx_count = 0;
    tx_in_flight = false;
}

void dpls_phy6252_process_rx(void)
{
    dpls_rx_slot_t *slot;
    if (rx_count == 0u) return;
    slot = &rx_queue[rx_head];
    (void)dpls_server_receive(&server, slot->data, slot->length, now_ms());
    slot->length = 0;
    rx_head = (uint8)((rx_head + 1u) % DPLS_RX_QUEUE_DEPTH);
    --rx_count;
    if (rx_count != 0u) osal_set_event(task_id, DPLS_PHY6252_RX_EVT);
}

void dpls_phy6252_process_adc(void)
{
    dpls_phy6252_adc_process(now_ms());
}

void dpls_phy6252_tick(void)
{
    uint32_t now = now_ms();

    if (connection_handle != INVALID_CONNHANDLE) {
        if (link_encrypted(NULL)) connection_had_encryption = true;
        if (server.authenticated) {
            pre_auth_disconnect_count = 0;
            pre_auth_disconnect_window_ms = 0;
        }
    }
    if (connection_handle != INVALID_CONNHANDLE && !link_encrypted(NULL) && connected_at_ms != 0u &&
        (uint32_t)(now - connected_at_ms) >= DPLS_LINK_ENCRYPT_TIMEOUT_MS) {
        connected_at_ms = 0;
        erase_bonds_and_drop_link();
    }
    if (factory_reset_armed) {
        if (!hal_gpio_read(DPLS_FACTORY_RESET_PIN)) factory_reset_armed = false;
        else if ((uint32_t)(now - factory_reset_started_ms) >= DPLS_FACTORY_RESET_HOLD_MS) {
            factory_reset_armed = false;
            clear_settings_and_bonds();
        }
    }

    dpls_phy6252_adc_tick(now);
    update_power_state(now);
    dpls_server_tick(&server, now);

    if (tx_in_flight && (uint32_t)(now - tx_in_flight_since_ms) >= DPLS_TX_CONFIRM_TIMEOUT_MS) {
        dpls_phy6252_tx_confirmed();
        return;
    }
    tx_pump();
}

uint32 dpls_phy6252_led_tick(void)
{
    uint32_t now = now_ms();
    dpls_led_set_identify(&status_led, identify_led_active, now);
    return dpls_led_tick(&status_led, now);
}
