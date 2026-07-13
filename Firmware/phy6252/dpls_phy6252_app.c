#include "dpls_phy6252_app.h"

#include "dpls_ble_identity.h"
#include "dpls_gatt_service.h"
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
#define DPLS_SETTINGS_SNV_ID 0x80u
#define DPLS_SETTINGS_STATE_SNV_ID 0x81u
#define DPLS_JOURNAL_FIRST_SNV_ID 0x90u
#define DPLS_JOURNAL_EVENTS_PER_BLOCK 10u
#define DPLS_JOURNAL_RECORD_SIZE 12u
#define DPLS_JOURNAL_BLOCK_COUNT (DPLS_EVENT_CAPACITY / DPLS_JOURNAL_EVENTS_PER_BLOCK)
#define DPLS_JOURNAL_BLOCK_SIZE (DPLS_JOURNAL_EVENTS_PER_BLOCK * DPLS_JOURNAL_RECORD_SIZE)
#define DPLS_NAME_SIZE 32u
#define DPLS_SETTINGS_EMPTY_MARKER 0x45u
#define DPLS_SETTINGS_VALID_MARKER 0x56u
#define DPLS_FACTORY_RESET_PIN GPIO_P14
#define DPLS_FACTORY_RESET_HOLD_MS 5000u
/* Drop stale NV bonds after repeated encrypted links that never reach DPLS auth. */
#define DPLS_BOND_DESYNC_LIMIT 3u
#define DPLS_BOND_DESYNC_WINDOW_MS 120000u
/* Plaintext link timeout — pairing never completed. */
#define DPLS_LINK_ENCRYPT_TIMEOUT_MS 15000u

/* PB-03F-Kit schematic: P11 is the green RGB LED and P7 is red. Relays are
 * deliberately not assigned on the bare evaluation board. */
#define DPLS_IDENTIFY_LED GPIO_P11
#define DPLS_MODE_LED GPIO_P07

typedef struct {
    uint32_t magic;
    char name[DPLS_NAME_SIZE];
    uint8_t salt[DPLS_AUTH_SALT_SIZE];
    uint8_t verifier[DPLS_AUTH_PROOF_SIZE];
    uint16_t crc;
} dpls_settings_t;

static dpls_server_t server;
static dpls_settings_t settings;
static uint16 connection_handle = INVALID_CONNHANDLE;
static uint8 task_id;
static dpls_mode_t hardware_mode = DPLS_MODE_NORMAL;
static dpls_settings_state_t settings_state = DPLS_SETTINGS_EMPTY;
static bool factory_reset_armed;
static uint32_t factory_reset_started_ms;
static uint32_t connected_at_ms;
static bool connection_had_encryption;
static uint8_t pre_auth_disconnect_count;
static uint32_t pre_auth_disconnect_window_ms;
#define DPLS_RX_QUEUE_DEPTH 4u
typedef struct { uint8 data[DPLS_MAX_FRAME]; uint16 length; } dpls_rx_slot_t;
static dpls_rx_slot_t rx_queue[DPLS_RX_QUEUE_DEPTH];
static uint8 rx_head, rx_tail, rx_count;
static uint8_t journal_block_cache[DPLS_JOURNAL_BLOCK_SIZE];
static uint8_t journal_cached_block = 0xffu;

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
    return event->sequence != 0u && event->event_type >= 1u && event->event_type <= 11u;
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

    /* First pass finds the newest individually checksummed record. */
    for (block_index = 0; block_index < DPLS_JOURNAL_BLOCK_COUNT; ++block_index) {
        /* Boot-time scan of 20 populated SNV blocks (plus the SDK's verbose
         * SNV UART tracing) takes longer than the 2 s watchdog window, and
         * init runs before the OSAL feed task exists. Feed per block or a
         * full journal makes the device unbootable. */
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

    /* Second pass builds only a 25-byte validity bitmap. No event array is
     * retained in RAM. A torn/corrupt record truncates the recovered history
     * at that point instead of exporting stale bytes. */
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
    hardware_mode = DPLS_MODE_NORMAL;
    hal_gpio_write(DPLS_MODE_LED, 0);
}

static bool apply_mode(void *context, dpls_mode_t mode)
{
    (void)context;
    if (mode > DPLS_MODE_SHORT_T) return false;
    /* Evaluation-board build: expose and exercise the complete BLE state
     * machine, but never energize an unassigned relay output. */
    hardware_mode = mode;
    hal_gpio_write(DPLS_MODE_LED, mode == DPLS_MODE_NORMAL ? 0 : 1);
    return true;
}

static uint16_t voltage_mv(void *context)
{
    (void)context;
    return 0;
}

static dpls_power_t power_source(void *context)
{
    (void)context;
    return DPLS_POWER_LINE;
}

static bool reserve_low(void *context)
{
    (void)context;
    return false;
}

static void identify_led(void *context, bool enabled)
{
    (void)context;
    hal_gpio_write(DPLS_IDENTIFY_LED, enabled ? 1 : 0);
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

static bool write_settings(void *context, const char *name, const uint8_t salt[16], const uint8_t verifier[32])
{
    size_t name_length;
    dpls_settings_t verified;
    uint8 marker = DPLS_SETTINGS_VALID_MARKER;
    (void)context;
    memset(&settings, 0, sizeof(settings));
    settings.magic = DPLS_SETTINGS_MAGIC;
    name_length = strlen(name);
    if (name_length >= DPLS_NAME_SIZE) name_length = DPLS_NAME_SIZE - 1u;
    memcpy(settings.name, name, name_length);
    memcpy(settings.salt, salt, DPLS_AUTH_SALT_SIZE);
    memcpy(settings.verifier, verifier, DPLS_AUTH_PROOF_SIZE);
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

static bool tx_indicate(void *context, const uint8_t *frame, size_t length)
{
    (void)context;
    return dpls_gatt_send_indication(connection_handle, frame, (uint16)length, task_id);
}

static bool tx_notify(void *context, const uint8_t *frame, size_t length)
{
    (void)context;
    return dpls_gatt_send_notification(connection_handle, frame, (uint16)length, task_id);
}

static void receive_frame(const uint8 *data, uint16 length)
{
    dpls_rx_slot_t *slot;
    if (length > DPLS_MAX_FRAME || rx_count >= DPLS_RX_QUEUE_DEPTH) return;
    slot = &rx_queue[rx_tail];
    memcpy(slot->data, data, length);
    slot->length = length;
    rx_tail = (uint8)((rx_tail + 1u) % DPLS_RX_QUEUE_DEPTH);
    ++rx_count;
    osal_set_event(task_id, DPLS_PHY6252_RX_EVT);
}

static void clear_settings_and_bonds(void)
{
    uint8 marker = DPLS_SETTINGS_EMPTY_MARKER;
    memset(&settings, 0, sizeof(settings));
    /* The explicit marker is written only by the physical reset path. A bad
     * settings record never becomes remotely commissionable by accident. */
    (void)osal_snv_write(DPLS_SETTINGS_SNV_ID, sizeof(settings), &settings);
    (void)osal_snv_write(DPLS_SETTINGS_STATE_SNV_ID, sizeof(marker), &marker);
    settings_state = DPLS_SETTINGS_EMPTY;
    GAPBondMgr_SetParameter(GAPBOND_ERASE_ALLBONDS, 0, NULL);
    dpls_ble_identity_reset_bonding_keys();
    hal_gpio_write(DPLS_IDENTIFY_LED, 1);
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
    hal_gpio_pin_init(DPLS_IDENTIFY_LED, OEN);
    hal_gpio_pin_init(DPLS_MODE_LED, OEN);
    hal_gpio_pin_init(DPLS_FACTORY_RESET_PIN, IE);
    hal_gpio_pull_set(DPLS_FACTORY_RESET_PIN, GPIO_PULL_DOWN);
    hal_gpio_write(DPLS_IDENTIFY_LED, 0);
    hal_gpio_write(DPLS_MODE_LED, 0);
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
    hal.power_source = power_source;
    hal.reserve_low = reserve_low;
    hal.identify_led = identify_led;
    hal.random_bytes = random_bytes;
    hal.settings_state = get_settings_state;
    hal.settings_salt = settings_salt;
    hal.settings_write = write_settings;
    hal.verify_auth_proof = verify_proof;
    hal.event_storage_init = journal_storage_init;
    hal.event_storage_append = journal_storage_append;
    hal.event_storage_read = journal_storage_read;
    hal.tx_indicate = tx_indicate;
    hal.tx_notify = tx_notify;
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
    if (connection_handle != INVALID_CONNHANDLE) {
        (void)GAPRole_TerminateConnection();
    }
}

static void note_pre_auth_disconnect(void)
{
    uint32_t now = now_ms();
    if (server.authenticated || !connection_had_encryption) {
        return;
    }
    if (pre_auth_disconnect_window_ms == 0u ||
        (uint32_t)(now - pre_auth_disconnect_window_ms) > DPLS_BOND_DESYNC_WINDOW_MS) {
        pre_auth_disconnect_count = 0;
        pre_auth_disconnect_window_ms = now;
    }
    if (++pre_auth_disconnect_count < DPLS_BOND_DESYNC_LIMIT) {
        return;
    }
    pre_auth_disconnect_count = 0;
    pre_auth_disconnect_window_ms = 0;
    erase_stored_bonds();
}

void dpls_phy6252_connected(uint16 conn_handle)
{
    connection_handle = conn_handle;
    connected_at_ms = now_ms();
    connection_had_encryption = false;
    dpls_server_connected(&server, now_ms());
}

void dpls_phy6252_disconnected(void)
{
    note_pre_auth_disconnect();
    dpls_server_disconnected(&server, now_ms());
    connection_handle = INVALID_CONNHANDLE;
    connected_at_ms = 0;
    connection_had_encryption = false;
    rx_head = rx_tail = rx_count = 0;
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

void dpls_phy6252_tick(void)
{
    if (connection_handle != INVALID_CONNHANDLE) {
        if (link_encrypted(NULL)) {
            connection_had_encryption = true;
        }
        if (server.authenticated) {
            pre_auth_disconnect_count = 0;
            pre_auth_disconnect_window_ms = 0;
        }
    }
    if (connection_handle != INVALID_CONNHANDLE && !link_encrypted(NULL) && connected_at_ms != 0u &&
        (uint32_t)(now_ms() - connected_at_ms) >= DPLS_LINK_ENCRYPT_TIMEOUT_MS) {
        connected_at_ms = 0;
        erase_bonds_and_drop_link();
    }
    if (factory_reset_armed) {
        if (!hal_gpio_read(DPLS_FACTORY_RESET_PIN)) factory_reset_armed = false;
        else if ((uint32_t)(now_ms() - factory_reset_started_ms) >= DPLS_FACTORY_RESET_HOLD_MS) {
            factory_reset_armed = false;
            clear_settings_and_bonds();
        }
    }
    dpls_server_tick(&server, now_ms());
}
