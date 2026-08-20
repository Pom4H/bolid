#include "dpls_phy6252_app.h"

#include "dpls_ble_identity.h"
#include "dpls_board.h"
#include "dpls_calib.h"
#include "dpls_gatt_service.h"
#include "dpls_led.h"
#include "dpls_server.h"
#include "OSAL.h"
#include "log.h"
#if defined(__GNUC__)
#pragma GCC diagnostic ignored "-Wunused-function"
#endif
#include "adc.h"
#include "error.h"
#include "gpio.h"
#include "linkdb.h"
#include "ll_enc.h"
#include "osal_snv.h"
#include "watchdog.h"
#include "gapbondmgr.h"
#include "peripheral.h"
#include "pwrmgr.h"
#include <core_cm0.h>
#include <tinycrypt/hmac.h>
#include <stddef.h>
#include <string.h>

#define DPLS_SETTINGS_MAGIC 0x534C5044u
#define DPLS_CALIB_MAGIC 0x434C5044u
#define DPLS_AUTH_LOCK_MAGIC 0x4B434C44u
#define DPLS_SETTINGS_SNV_ID 0x80u
#define DPLS_SETTINGS_STATE_SNV_ID 0x81u
#define DPLS_CALIB_SNV_ID 0x83u
#define DPLS_AUTH_LOCK_SNV_ID 0x84u
#define DPLS_JOURNAL_FIRST_SNV_ID 0x90u
#define DPLS_JOURNAL_EVENTS_PER_BLOCK 10u
#define DPLS_JOURNAL_RECORD_SIZE 12u
#define DPLS_JOURNAL_BLOCK_COUNT (DPLS_EVENT_CAPACITY / DPLS_JOURNAL_EVENTS_PER_BLOCK)
#define DPLS_JOURNAL_BLOCK_SIZE (DPLS_JOURNAL_EVENTS_PER_BLOCK * DPLS_JOURNAL_RECORD_SIZE)
/* Events generated during a BLE session stay in RAM. Overflow is a storage
 * failure and the protocol server fails safe rather than silently dropping an
 * acknowledged audit event. */
#define DPLS_PENDING_EVENT_CAPACITY 24u
#define DPLS_NAME_SIZE 32u
#define DPLS_HW_REVISION 2u
#define DPLS_SETTINGS_EMPTY_MARKER 0x45u
#define DPLS_SETTINGS_VALID_MARKER 0x56u
#define DPLS_FACTORY_RESET_PIN DPLS_PIN_FACTORY_RESET
#define DPLS_FACTORY_RESET_HOLD_MS 5000u
#define DPLS_LED_TICK_MIN_MS 10u
#define DPLS_LED_TICK_MAX_MS 250u
#define DPLS_ADC_DECIMATE 1u
#define DPLS_ADC_WINDOW 8u
#define DPLS_ADC_NEED_PORT1 0x01u
#define DPLS_ADC_NEED_PORT2 0x02u
#define DPLS_ADC_NEED_PORT_T 0x04u
#define DPLS_ADC_NEED_VCAP 0x08u
#define DPLS_ADC_NEED_ALL (DPLS_ADC_NEED_PORT1 | DPLS_ADC_NEED_PORT2 | \
                           DPLS_ADC_NEED_PORT_T | DPLS_ADC_NEED_VCAP)
#define DPLS_MEASUREMENT_STALE_MS 3000u
#define DPLS_LINE_PRESENT_MV 4000u
#define DPLS_LINE_ABSENT_MV 3000u
#define DPLS_RESERVE_LOW_MV 3700u
#define DPLS_RESERVE_OK_MV 4000u
#define DPLS_VCAP_NOMINAL_GAIN_MILLI 2000u
#define DPLS_AUTOISO_TRIP_MV 3000u
#define DPLS_AUTOISO_CLEAR_MV 4500u
/* Pairing is owned by the phone/security stack. Firmware only limits a truly
 * abandoned plaintext ACL. This is deliberately longer than the 45 s mobile
 * handshake timeout and never erases bonds. */
#define DPLS_LINK_ENCRYPT_TIMEOUT_MS 60000u

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

static dpls_server_t server;
static dpls_settings_t settings;
static dpls_led_t status_led;
static bool identify_led_active;
/* connection_handle is the single authoritative physical-link owner. Target
 * scheduling queries dpls_phy6252_link_active() instead of maintaining link_up. */
static uint16 connection_handle = INVALID_CONNHANDLE;
static uint8 task_id;
/* server.safety.mode is the single authoritative commanded-mode owner. */
static dpls_settings_state_t settings_state = DPLS_SETTINGS_EMPTY;
static bool factory_reset_armed;
static uint32_t factory_reset_started_ms;
static uint32_t connected_at_ms;
static bool connection_had_encryption;

#define DPLS_RX_QUEUE_DEPTH 6u
#define DPLS_RX_SLOT_SIZE 96u
typedef struct { uint8 data[DPLS_RX_SLOT_SIZE]; uint16 length; } dpls_rx_slot_t;
typedef struct {
    dpls_rx_slot_t slots[DPLS_RX_QUEUE_DEPTH];
    uint8 head;
    uint8 tail;
    uint8 count;
} dpls_rx_queue_t;
static dpls_rx_queue_t rx;

#define DPLS_TX_QUEUE_DEPTH 4u
#define DPLS_TX_SLOT_SIZE 168u
typedef struct { uint16 length; uint8 data[DPLS_TX_SLOT_SIZE]; } dpls_tx_slot_t;
typedef struct {
    dpls_tx_slot_t slots[DPLS_TX_QUEUE_DEPTH];
    uint8 head;
    uint8 tail;
    uint8 count;
    bool in_flight;
    uint32_t in_flight_since_ms;
} dpls_tx_queue_t;
#define DPLS_TX_CONFIRM_TIMEOUT_MS 2000u
#define DPLS_TX_NOTIFY_PACE_MS 80u
static dpls_tx_queue_t tx;

static dpls_event_t journal_pending_events[DPLS_PENDING_EVENT_CAPACITY];
static uint8_t journal_pending_event_count;
static uint8_t journal_block_cache[DPLS_JOURNAL_BLOCK_SIZE];
static uint8_t journal_cached_block = 0xffu;

static dpls_calib_t line_calib;
static dpls_calib_t vcap_calib;
static uint16_t line_window[DPLS_ADC_WINDOW];
static uint16_t port2_window[DPLS_ADC_WINDOW];
static uint16_t port_t_window[DPLS_ADC_WINDOW];
static uint16_t vcap_window[DPLS_ADC_WINDOW];
static uint8_t line_window_count, line_window_pos;
static uint8_t port2_window_count, port2_window_pos;
static uint8_t port_t_window_count, port_t_window_pos;
static uint8_t vcap_window_count, vcap_window_pos;
static uint32_t line_last_sample_ms;
static uint32_t port2_last_sample_ms;
static uint32_t port_t_last_sample_ms;
static uint32_t vcap_last_sample_ms;
static volatile uint16_t cached_line_mv;
static volatile uint16_t cached_port2_mv;
static volatile uint16_t cached_port_t_mv;
static volatile uint16_t cached_vcap_mv;
static volatile bool adc_busy;
static uint8_t adc_pending;
static uint8_t adc_decimate;
static dpls_power_t power_state = DPLS_POWER_LINE;
static bool reserve_low_state;
static bool auto_isolation_active;
static bool line_established;
static bool line_calib_from_nv;
static bool control_sleep_locked;

static uint32_t now_ms(void) { return (uint32_t)osal_GetSystemClock(); }

#if DPLS_EVENT_CAPACITY != 200u
#error "PHY6252 journal layout is defined for exactly 200 events"
#endif

/* Keep the SDK's normal 2 s watchdog policy, but a synchronous settings/SNV
 * transaction is a known blocking resource. Widen only around that call. Journal
 * writes use this too, but now only after disconnect with advertising disabled. */
static uint8_t snv_write_bounded(osalSnvId_t id, osalSnvLen_t len, void *data)
{
    uint8_t rc;
    (void)watchdog_config(WDG_8S);
    hal_watchdog_feed();
    rc = osal_snv_write(id, len, data);
    hal_watchdog_feed();
    (void)watchdog_config(WDG_2S);
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

static uint8_t *journal_scan_block(uint16_t block_index)
{
    hal_watchdog_feed();
    return journal_load_block((uint8_t)block_index);
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
        uint8_t *block = journal_scan_block(block_index);
        for (record_index = 0; record_index < DPLS_JOURNAL_EVENTS_PER_BLOCK; ++record_index) {
            uint16_t slot = (uint16_t)(block_index * DPLS_JOURNAL_EVENTS_PER_BLOCK + record_index);
            if (journal_record_in_slot(block + record_index * DPLS_JOURNAL_RECORD_SIZE, slot, &event) &&
                event.sequence > max_sequence) max_sequence = event.sequence;
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
        uint8_t *block = journal_scan_block(block_index);
        for (record_index = 0; record_index < DPLS_JOURNAL_EVENTS_PER_BLOCK; ++record_index) {
            uint16_t slot = (uint16_t)(block_index * DPLS_JOURNAL_EVENTS_PER_BLOCK + record_index);
            uint32_t age;
            if (!journal_record_in_slot(block + record_index * DPLS_JOURNAL_RECORD_SIZE, slot, &event) ||
                event.sequence > max_sequence) continue;
            age = max_sequence - event.sequence;
            if (age < DPLS_EVENT_CAPACITY)
                present[age / 8u] |= (uint8_t)(1u << (age % 8u));
        }
    }
    while (count < DPLS_EVENT_CAPACITY &&
           (present[count / 8u] & (uint8_t)(1u << (count % 8u)))) ++count;
    return count;
}

static bool journal_storage_init(void *context, uint16_t *count, uint32_t *next_sequence)
{
    uint32_t max_sequence;
    (void)context;
    journal_cached_block = 0xffu;
    journal_pending_event_count = 0u;
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

static bool journal_storage_append(void *context, const dpls_event_t *event)
{
    (void)context;
    if (!event || event->sequence == 0u ||
        journal_pending_event_count >= DPLS_PENDING_EVENT_CAPACITY) return false;
    journal_pending_events[journal_pending_event_count++] = *event;
    return true;
}

static bool journal_storage_read(void *context, uint32_t sequence, dpls_event_t *event)
{
    uint8_t *block;
    uint16_t slot;
    uint8_t block_index, record_index, i;
    (void)context;
    if (!event || sequence == 0u) return false;
    for (i = journal_pending_event_count; i > 0u; --i) {
        if (journal_pending_events[i - 1u].sequence == sequence) {
            *event = journal_pending_events[i - 1u];
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

/* Commit exactly one journal block. The target keeps advertising disabled while
 * this event is pending; checking the authoritative link owner here is the last
 * line of defence against a connected flash write. */
static bool journal_flush_one_block(void)
{
    uint8_t block_index, applied = 0u, i;
    uint8_t *block;
    if (journal_pending_event_count == 0u) return true;
    if (connection_handle != INVALID_CONNHANDLE) return false;

    block_index = (uint8_t)(((journal_pending_events[0].sequence - 1u) % DPLS_EVENT_CAPACITY) /
                            DPLS_JOURNAL_EVENTS_PER_BLOCK);
    block = journal_load_block(block_index);
    for (i = 0u; i < journal_pending_event_count; ++i) {
        uint16_t slot = (uint16_t)((journal_pending_events[i].sequence - 1u) % DPLS_EVENT_CAPACITY);
        uint8_t event_block = (uint8_t)(slot / DPLS_JOURNAL_EVENTS_PER_BLOCK);
        uint8_t record_index;
        if (event_block != block_index) break;
        record_index = (uint8_t)(slot % DPLS_JOURNAL_EVENTS_PER_BLOCK);
        journal_encode_record(block + record_index * DPLS_JOURNAL_RECORD_SIZE,
                              &journal_pending_events[i]);
        ++applied;
    }
    if (applied == 0u) return false;
    if (snv_write_bounded((osalSnvId_t)(DPLS_JOURNAL_FIRST_SNV_ID + block_index),
                          (osalSnvLen_t)DPLS_JOURNAL_BLOCK_SIZE, block) != SUCCESS) {
        journal_cached_block = 0xffu;
        return false;
    }
    memmove(journal_pending_events, journal_pending_events + applied,
            (size_t)(journal_pending_event_count - applied) * sizeof(journal_pending_events[0]));
    journal_pending_event_count = (uint8_t)(journal_pending_event_count - applied);
    return true;
}

bool dpls_phy6252_link_active(void)
{
    return connection_handle != INVALID_CONNHANDLE;
}

bool dpls_phy6252_storage_pending(void)
{
    return journal_pending_event_count != 0u;
}

void dpls_phy6252_process_storage(void)
{
    if (dpls_phy6252_link_active() || journal_pending_event_count == 0u) return;
    if (!journal_flush_one_block()) {
        osal_start_timerEx(task_id, DPLS_PHY6252_STORAGE_EVT, 1000u);
        return;
    }
    if (journal_pending_event_count != 0u) osal_set_event(task_id, DPLS_PHY6252_STORAGE_EVT);
}

static bool link_encrypted(void *context)
{
    (void)context;
    return dpls_phy6252_link_active() && linkDB_Encrypted(connection_handle);
}

static void mode_outputs_off(void)
{
    hal_gpio_write(DPLS_PIN_ISO_1, 0);
    hal_gpio_write(DPLS_PIN_ISO_2, 0);
    hal_gpio_write(DPLS_PIN_ISO_T, 0);
    hal_gpio_write(DPLS_PIN_KZ_1, 0);
    hal_gpio_write(DPLS_PIN_KZ_2, 0);
    hal_gpio_write(DPLS_PIN_KZ_T, 0);
}

static void control_sleep_guard(bool energized)
{
    if (energized == control_sleep_locked) return;
    if (energized) {
        if (hal_pwrmgr_lock(MOD_USR1) == PPlus_SUCCESS) control_sleep_locked = true;
    } else {
        if (hal_pwrmgr_unlock(MOD_USR1) == PPlus_SUCCESS) control_sleep_locked = false;
    }
}

static void safe_normal(void *context)
{
    (void)context;
    mode_outputs_off();
    control_sleep_guard(false);
}

static bool apply_mode(void *context, dpls_mode_t mode)
{
    (void)context;
    if (mode > DPLS_MODE_SHORT_T) return false;
    mode_outputs_off();
    switch (mode) {
    case DPLS_MODE_NORMAL: break;
    case DPLS_MODE_OPEN_T: hal_gpio_write(DPLS_PIN_ISO_T, 1); break;
    case DPLS_MODE_OPEN_MAIN: hal_gpio_write(DPLS_PIN_ISO_2, 1); break;
    case DPLS_MODE_SHORT_1: hal_gpio_write(DPLS_PIN_KZ_1, 1); break;
    case DPLS_MODE_SHORT_2: hal_gpio_write(DPLS_PIN_KZ_2, 1); break;
    case DPLS_MODE_SHORT_T: hal_gpio_write(DPLS_PIN_KZ_T, 1); break;
    default: return false;
    }
    control_sleep_guard(mode != DPLS_MODE_NORMAL);
    LOG("DPLS MODE %u\n", (unsigned)mode);
    return true;
}

static dpls_led_scene_t led_scene_for_mode(dpls_mode_t mode)
{
    switch (mode) {
    case DPLS_MODE_OPEN_T: return DPLS_LED_SCENE_OPEN_T;
    case DPLS_MODE_OPEN_MAIN: return DPLS_LED_SCENE_OPEN_MAIN;
    case DPLS_MODE_SHORT_1: return DPLS_LED_SCENE_SHORT_1;
    case DPLS_MODE_SHORT_2: return DPLS_LED_SCENE_SHORT_2;
    case DPLS_MODE_SHORT_T: return DPLS_LED_SCENE_SHORT_T;
    default: return DPLS_LED_SCENE_NORMAL;
    }
}

static void status_led_output(void *context, bool on)
{
    (void)context;
    hal_gpio_write(DPLS_PIN_LED_RED, 0);
    hal_gpio_write(DPLS_PIN_LED_BLUE, 0);
    hal_gpio_write(DPLS_PIN_LED_GREEN, on ? 1 : 0);
    LOG("DPLS LED %u\n", on ? 1u : 0u);
}

static void load_calibration(void)
{
    dpls_calib_nv_t nv;
    dpls_calib_default(&line_calib);
    dpls_calib_default(&vcap_calib);
    vcap_calib.gain_milli = DPLS_VCAP_NOMINAL_GAIN_MILLI;
    line_calib_from_nv = false;
    if (osal_snv_read(DPLS_CALIB_SNV_ID, sizeof(nv), &nv) == SUCCESS &&
        nv.magic == DPLS_CALIB_MAGIC &&
        nv.crc == dpls_crc16((const uint8_t *)&nv, offsetof(dpls_calib_nv_t, crc))) {
        dpls_calib_t line = {nv.line_gain_milli, nv.line_offset_mv};
        dpls_calib_t vcap = {nv.vcap_gain_milli, nv.vcap_offset_mv};
        if (dpls_calib_valid(&line)) { line_calib = line; line_calib_from_nv = true; }
        if (dpls_calib_valid(&vcap)) vcap_calib = vcap;
    }
}

static uint16_t fold_window(uint16_t *window, uint8_t *count, uint8_t *pos, uint16_t value)
{
    uint32_t sum = 0u;
    uint8_t i;
    window[*pos] = value;
    *pos = (uint8_t)((*pos + 1u) % DPLS_ADC_WINDOW);
    if (*count < DPLS_ADC_WINDOW) ++*count;
    for (i = 0u; i < *count; ++i) sum += window[i];
    return (uint16_t)(sum / *count);
}

static bool sample_fresh(uint8_t count, uint32_t last_sample_ms, uint32_t now)
{
    return count != 0u && (uint32_t)(now - last_sample_ms) <= DPLS_MEASUREMENT_STALE_MS;
}

static volatile uint16_t adc_raw[MAX_ADC_SAMPLE_SIZE];
static volatile uint8_t adc_raw_size;
static volatile adc_CH_t adc_raw_channel;
static volatile bool adc_raw_ready;

static void adc_evt(adc_Evt_t *event)
{
    uint8_t i, n;
    if (event->type != HAL_ADC_EVT_DATA) { adc_busy = false; return; }
    n = event->size > MAX_ADC_SAMPLE_SIZE ? MAX_ADC_SAMPLE_SIZE : event->size;
    for (i = 0; i < n; ++i) adc_raw[i] = event->data[i];
    adc_raw_size = n;
    adc_raw_channel = event->ch;
    adc_raw_ready = true;
    adc_busy = false;
    osal_set_event(task_id, DPLS_PHY6252_ADC_EVT);
}

static void process_adc_channel(adc_CH_t ch, volatile uint16_t *raw, uint8_t size,
                                const dpls_calib_t *calib, uint16_t *window,
                                uint8_t *wcount, uint8_t *wpos, volatile uint16_t *cached)
{
    float pin_volts = hal_adc_value_cal(ch, (uint16_t *)raw, size, FALSE, FALSE);
    uint32_t pin_mv = pin_volts <= 0.0f ? 0u : (uint32_t)(pin_volts * 1000.0f + 0.5f);
    *cached = fold_window(window, wcount, wpos, dpls_calib_apply(calib, pin_mv));
}

static void adc_kick(void)
{
    adc_Cfg_t cfg;
    uint8_t channel;
    uint8_t claim;
    if (adc_busy || adc_raw_ready || adc_pending == 0u) return;
    memset(&cfg, 0, sizeof(cfg));
    if (adc_pending & DPLS_ADC_NEED_PORT1) {
        channel = ADC_BIT(DPLS_ADC_CHANNEL(DPLS_PIN_PORT1_ADC)); claim = DPLS_ADC_NEED_PORT1;
    } else if (adc_pending & DPLS_ADC_NEED_PORT2) {
        channel = ADC_BIT(DPLS_ADC_CHANNEL(DPLS_PIN_PORT2_ADC)); claim = DPLS_ADC_NEED_PORT2;
    } else if (adc_pending & DPLS_ADC_NEED_PORT_T) {
        channel = ADC_BIT(DPLS_ADC_CHANNEL(DPLS_PIN_PORT_T_ADC)); claim = DPLS_ADC_NEED_PORT_T;
    } else {
        channel = ADC_BIT(DPLS_ADC_CHANNEL(DPLS_PIN_VCAP_ADC)); claim = DPLS_ADC_NEED_VCAP;
    }
    cfg.channel = channel;
    cfg.is_continue_mode = FALSE;
    cfg.is_differential_mode = 0u;
    cfg.is_high_resolution = 0u;
    adc_busy = true;
    if (hal_adc_config_channel(cfg, adc_evt) != PPlus_SUCCESS) { adc_busy = false; return; }
    if (hal_adc_start(INTERRUPT_MODE) != PPlus_SUCCESS) {
        (void)hal_adc_stop(); adc_busy = false; return;
    }
    adc_pending = (uint8_t)(adc_pending & (uint8_t)~claim);
}

void dpls_phy6252_process_adc(void)
{
    if (adc_raw_ready) {
        adc_CH_t ch = adc_raw_channel;
        uint8_t size = adc_raw_size;
        uint32_t sample_ms = now_ms();
        adc_raw_ready = false;
        switch (ch) {
        case DPLS_ADC_CHANNEL(DPLS_PIN_PORT1_ADC):
            process_adc_channel(ch, adc_raw, size, &line_calib, line_window,
                                &line_window_count, &line_window_pos, &cached_line_mv);
            line_last_sample_ms = sample_ms;
            break;
        case DPLS_ADC_CHANNEL(DPLS_PIN_PORT2_ADC):
            process_adc_channel(ch, adc_raw, size, &line_calib, port2_window,
                                &port2_window_count, &port2_window_pos, &cached_port2_mv);
            port2_last_sample_ms = sample_ms;
            break;
        case DPLS_ADC_CHANNEL(DPLS_PIN_PORT_T_ADC):
            process_adc_channel(ch, adc_raw, size, &line_calib, port_t_window,
                                &port_t_window_count, &port_t_window_pos, &cached_port_t_mv);
            port_t_last_sample_ms = sample_ms;
            break;
        case DPLS_ADC_CHANNEL(DPLS_PIN_VCAP_ADC):
            process_adc_channel(ch, adc_raw, size, &vcap_calib, vcap_window,
                                &vcap_window_count, &vcap_window_pos, &cached_vcap_mv);
            vcap_last_sample_ms = sample_ms;
            break;
        default: break;
        }
    }
    adc_kick();
}

static void update_power_state(void)
{
    uint32_t now = now_ms();
    if (sample_fresh(line_window_count, line_last_sample_ms, now)) {
        uint16_t line = cached_line_mv;
        if (power_state == DPLS_POWER_LINE && line < DPLS_LINE_ABSENT_MV)
            power_state = DPLS_POWER_RESERVE;
        else if (power_state == DPLS_POWER_RESERVE && line > DPLS_LINE_PRESENT_MV)
            power_state = DPLS_POWER_LINE;
        if (line > DPLS_LINE_PRESENT_MV) line_established = true;
        if (line_established && server.safety.mode == DPLS_MODE_NORMAL) {
            if (!auto_isolation_active && line < DPLS_AUTOISO_TRIP_MV) auto_isolation_active = true;
            else if (auto_isolation_active && line > DPLS_AUTOISO_CLEAR_MV) auto_isolation_active = false;
        }
    }
    if (sample_fresh(vcap_window_count, vcap_last_sample_ms, now)) {
        uint16_t vcap = cached_vcap_mv;
        if (!reserve_low_state && vcap < DPLS_RESERVE_LOW_MV) reserve_low_state = true;
        else if (reserve_low_state && vcap > DPLS_RESERVE_OK_MV) reserve_low_state = false;
    }
}

static uint16_t voltage_mv(void *context) { (void)context; return cached_line_mv; }
static uint16_t port1_voltage_mv(void *context) { (void)context; return cached_line_mv; }
static uint16_t port2_voltage_mv(void *context) { (void)context; return cached_port2_mv; }
static uint16_t port_t_voltage_mv(void *context) { (void)context; return cached_port_t_mv; }
static uint16_t reserve_voltage_mv(void *context) { (void)context; return cached_vcap_mv; }
static dpls_power_t power_source(void *context) { (void)context; return power_state; }
static bool reserve_low(void *context) { (void)context; return reserve_low_state; }
static bool real_short_active(void *context) { (void)context; return auto_isolation_active; }

static uint8_t measurement_validity(void *context)
{
    uint8_t flags = 0;
    uint32_t now = now_ms();
    (void)context;
    if (sample_fresh(line_window_count, line_last_sample_ms, now))
        flags |= DPLS_STATE_PORT_1_VALID | DPLS_STATE_POWER_VALID | DPLS_STATE_AUTOISO_VALID;
    if (sample_fresh(port2_window_count, port2_last_sample_ms, now)) flags |= DPLS_STATE_PORT_2_VALID;
    if (sample_fresh(port_t_window_count, port_t_last_sample_ms, now)) flags |= DPLS_STATE_PORT_T_VALID;
    if (sample_fresh(vcap_window_count, vcap_last_sample_ms, now)) flags |= DPLS_STATE_RESERVE_VOLTAGE_VALID;
    if (line_calib_from_nv) flags |= DPLS_STATE_ADC_CALIBRATED;
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
            memset(out, 0, length); safe_normal(NULL); return false;
        }
        offset += chunk;
    }
    return true;
}

static dpls_settings_state_t get_settings_state(void *context)
{ (void)context; return settings_state; }

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
        memcpy(out, settings.name, DPLS_NAME_MAX); out[DPLS_NAME_MAX] = '\0';
    } else out[0] = '\0';
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
    if (line_calib_from_nv) out->capabilities |= DPLS_CAP_ADC_CALIBRATED;
}

static bool auth_lock_read(void *context)
{
    dpls_auth_lock_t record;
    (void)context;
    if (osal_snv_read(DPLS_AUTH_LOCK_SNV_ID, sizeof(record), &record) != SUCCESS) return false;
    if (record.magic != DPLS_AUTH_LOCK_MAGIC ||
        record.crc != dpls_crc16((const uint8_t *)&record, offsetof(dpls_auth_lock_t, crc))) return false;
    return record.locked != 0u;
}

static bool auth_lock_write(void *context, bool locked)
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

static bool verify_proof(void *context, const uint8_t device_nonce[16], const uint8_t client_nonce[16],
                         uint32_t session_id, const uint8_t proof[32])
{
    static struct tc_hmac_state_struct hmac;
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
        !tc_hmac_final(expected, sizeof(expected), &hmac)) {
        memset(&hmac, 0, sizeof(hmac)); return false;
    }
    for (i = 0; i < sizeof(expected); ++i) difference |= (uint8_t)(expected[i] ^ proof[i]);
    memset(&hmac, 0, sizeof(hmac));
    memset(expected, 0, sizeof(expected));
    memset(signed_data, 0, sizeof(signed_data));
    return difference == 0;
}

static void tx_complete_head(void)
{
    if (tx.count == 0u) { tx.in_flight = false; return; }
    tx.head = (uint8)((tx.head + 1u) % DPLS_TX_QUEUE_DEPTH);
    --tx.count;
    tx.in_flight = false;
}

static void tx_pump(void)
{
    bStatus_t rc;
    if (tx.in_flight || tx.count == 0u || !dpls_phy6252_link_active()) return;
    rc = dpls_gatt_send_indication(connection_handle, tx.slots[tx.head].data,
                                   tx.slots[tx.head].length, task_id);
    if (rc == SUCCESS) {
        tx.in_flight = true;
        tx.in_flight_since_ms = now_ms();
    } else if (rc == bleMemAllocError || rc == blePending || rc == MSG_BUFFER_NOT_AVAIL ||
               rc == bleNotConnected) {
        /* Keep head for the next TX event. */
    } else {
        LOG("DPLS TX drop t=%02x rc=%u\n",
            tx.slots[tx.head].length > 1u ? tx.slots[tx.head].data[1] : 0u, rc);
        tx_complete_head();
        if (tx.count) osal_set_event(task_id, DPLS_PHY6252_TX_EVT);
    }
}

static bool tx_indicate(void *context, const uint8_t *frame, size_t length)
{
    (void)context;
    if (length > DPLS_TX_SLOT_SIZE) return false;
    if (tx.count >= DPLS_TX_QUEUE_DEPTH) return false;
    memcpy(tx.slots[tx.tail].data, frame, length);
    tx.slots[tx.tail].length = (uint16)length;
    tx.tail = (uint8)((tx.tail + 1u) % DPLS_TX_QUEUE_DEPTH);
    ++tx.count;
    osal_set_event(task_id, DPLS_PHY6252_TX_EVT);
    return true;
}

void dpls_phy6252_process_tx(void)
{
    uint32_t elapsed_ms;
    if (tx.in_flight) {
        if (dpls_gatt_needs_confirmation(connection_handle)) return;
        elapsed_ms = (uint32_t)(now_ms() - tx.in_flight_since_ms);
        if (elapsed_ms < DPLS_TX_NOTIFY_PACE_MS) {
            osal_start_timerEx(task_id, DPLS_PHY6252_TX_EVT,
                               DPLS_TX_NOTIFY_PACE_MS - elapsed_ms);
            return;
        }
        /* Notification has no ATT confirmation by definition. This is pacing,
         * not a fabricated TX_CONFIRMED event. */
        tx_complete_head();
    }
    tx_pump();
    if (tx.in_flight && !dpls_gatt_needs_confirmation(connection_handle))
        osal_start_timerEx(task_id, DPLS_PHY6252_TX_EVT, DPLS_TX_NOTIFY_PACE_MS);
}

void dpls_phy6252_tx_confirmed(void)
{
    if (!tx.in_flight || !dpls_gatt_needs_confirmation(connection_handle)) return;
    LOG("DPLS CFM count=%u\n", tx.count);
    tx_complete_head();
    tx_pump();
}

static uint8 receive_frame(const uint8 *data, uint16 length)
{
    dpls_rx_slot_t *slot;
    if (length > DPLS_RX_SLOT_SIZE) return ATT_ERR_INVALID_VALUE_SIZE;
    if (rx.count >= DPLS_RX_QUEUE_DEPTH) return ATT_ERR_INSUFFICIENT_RESOURCES;
    slot = &rx.slots[rx.tail];
    memcpy(slot->data, data, length);
    slot->length = length;
    rx.tail = (uint8)((rx.tail + 1u) % DPLS_RX_QUEUE_DEPTH);
    ++rx.count;
    osal_set_event(task_id, DPLS_PHY6252_RX_EVT);
    return SUCCESS;
}

static void clear_settings_and_bonds(void)
{
    uint8 marker = DPLS_SETTINGS_EMPTY_MARKER;
    memset(&settings, 0, sizeof(settings));
    (void)snv_write_bounded(DPLS_SETTINGS_SNV_ID, sizeof(settings), &settings);
    (void)snv_write_bounded(DPLS_SETTINGS_STATE_SNV_ID, sizeof(marker), &marker);
    (void)auth_lock_write(NULL, false);
    settings_state = DPLS_SETTINGS_EMPTY;
    GAPBondMgr_SetParameter(GAPBOND_ERASE_ALLBONDS, 0, NULL);
    dpls_ble_identity_reset_bonding_keys();
    hal_gpio_write(DPLS_PIN_LED_GREEN, 1);
    NVIC_SystemReset();
}

static void disconnect_after_setup(void *context)
{ (void)context; (void)GAPRole_TerminateConnection(); }

static void classify_settings(void)
{
    uint16_t expected_crc;
    uint8 marker = 0;
    uint8 state_read;
    memset(&settings, 0, sizeof(settings));
    state_read = osal_snv_read(DPLS_SETTINGS_SNV_ID, sizeof(settings), &settings);
    if (state_read != SUCCESS) { settings_state = DPLS_SETTINGS_EMPTY; return; }
    expected_crc = dpls_crc16((const uint8_t *)&settings, offsetof(dpls_settings_t, crc));
    if (settings.magic == DPLS_SETTINGS_MAGIC && settings.crc == expected_crc) {
        settings_state = DPLS_SETTINGS_VALID; return;
    }
    if (osal_snv_read(DPLS_SETTINGS_STATE_SNV_ID, sizeof(marker), &marker) == SUCCESS &&
        marker == DPLS_SETTINGS_EMPTY_MARKER) { settings_state = DPLS_SETTINGS_EMPTY; return; }
    settings_state = DPLS_SETTINGS_CORRUPT;
    memset(&settings, 0, sizeof(settings));
}

static void initialize_retained_outputs(void)
{
    mode_outputs_off();
    hal_gpio_write(DPLS_PIN_LED_RED, 0);
    hal_gpio_write(DPLS_PIN_LED_GREEN, 0);
    hal_gpio_write(DPLS_PIN_LED_BLUE, 0);
    (void)hal_gpioretention_register(DPLS_PIN_ISO_1);
    (void)hal_gpioretention_register(DPLS_PIN_ISO_2);
    (void)hal_gpioretention_register(DPLS_PIN_ISO_T);
    (void)hal_gpioretention_register(DPLS_PIN_KZ_1);
    (void)hal_gpioretention_register(DPLS_PIN_KZ_2);
    (void)hal_gpioretention_register(DPLS_PIN_KZ_T);
    (void)hal_gpioretention_register(DPLS_PIN_LED_RED);
    (void)hal_gpioretention_register(DPLS_PIN_LED_GREEN);
    (void)hal_gpioretention_register(DPLS_PIN_LED_BLUE);
}

static void reset_measurements(void)
{
    line_window_count = line_window_pos = 0;
    port2_window_count = port2_window_pos = 0;
    port_t_window_count = port_t_window_pos = 0;
    vcap_window_count = vcap_window_pos = adc_decimate = 0;
    line_last_sample_ms = port2_last_sample_ms = port_t_last_sample_ms = vcap_last_sample_ms = 0u;
    cached_line_mv = cached_port2_mv = cached_port_t_mv = cached_vcap_mv = 0;
    adc_pending = 0u;
    adc_raw_ready = false;
    adc_busy = false;
    power_state = DPLS_POWER_LINE;
    reserve_low_state = false;
    auto_isolation_active = false;
    line_established = false;
}

static dpls_hal_t server_hal(void)
{
    dpls_hal_t hal;
    memset(&hal, 0, sizeof(hal));
    hal.link.encrypted = link_encrypted;
    hal.link.indicate = tx_indicate;
    hal.link.disconnect = disconnect_after_setup;
    hal.hardware.apply_mode = apply_mode;
    hal.hardware.safe_normal = safe_normal;
    hal.hardware.voltage_mv = voltage_mv;
    hal.hardware.port1_voltage_mv = port1_voltage_mv;
    hal.hardware.port2_voltage_mv = port2_voltage_mv;
    hal.hardware.port_t_voltage_mv = port_t_voltage_mv;
    hal.hardware.reserve_voltage_mv = reserve_voltage_mv;
    hal.hardware.power_source = power_source;
    hal.hardware.reserve_low = reserve_low;
    hal.hardware.measurement_validity = measurement_validity;
    hal.hardware.real_short_active = real_short_active;
    hal.hardware.identify_led = identify_led;
    hal.hardware.device_info = device_info;
    hal.settings.state = get_settings_state;
    hal.settings.salt = settings_salt;
    hal.settings.write = write_settings;
    hal.settings.name = settings_name;
    hal.settings.set_name = settings_set_name;
    hal.settings.set_password = settings_set_password;
    hal.auth.random_bytes = random_bytes;
    hal.auth.verify_proof = verify_proof;
    hal.auth.lock_read = auth_lock_read;
    hal.auth.lock_write = auth_lock_write;
    hal.events.init = journal_storage_init;
    hal.events.append = journal_storage_append;
    hal.events.read = journal_storage_read;
    return hal;
}

void dpls_phy6252_init(uint8 new_task_id)
{
    dpls_hal_t hal = server_hal();
    task_id = new_task_id;
    connection_handle = INVALID_CONNHANDLE;
    memset(&rx, 0, sizeof(rx));
    memset(&tx, 0, sizeof(tx));
    journal_pending_event_count = 0u;
    journal_cached_block = 0xffu;
    identify_led_active = false;
    initialize_retained_outputs();
    dpls_led_init(&status_led, status_led_output, NULL, now_ms());
    reset_measurements();
    load_calibration();
    hal_adc_init();
    hal_gpio_pin_init(DPLS_FACTORY_RESET_PIN, IE);
    hal_gpio_pull_set(DPLS_FACTORY_RESET_PIN, GPIO_PULL_DOWN);
    classify_settings();
    factory_reset_armed = hal_gpio_read(DPLS_FACTORY_RESET_PIN);
    factory_reset_started_ms = now_ms();
    if (settings_state == DPLS_SETTINGS_EMPTY) {
        GAPBondMgr_SetParameter(GAPBOND_ERASE_ALLBONDS, 0, NULL);
        dpls_ble_identity_reset_bonding_keys();
    }
    dpls_server_init(&server, &hal, now_ms());
    (void)dpls_gatt_add_service(receive_frame);
    if (journal_pending_event_count != 0u) osal_set_event(task_id, DPLS_PHY6252_STORAGE_EVT);
    LOG("DPLS boot settings=%u\n", (unsigned)settings_state);
}

void dpls_phy6252_connected(uint16 conn_handle)
{
    connection_handle = conn_handle;
    connected_at_ms = now_ms();
    connection_had_encryption = false;
    dpls_server_connected(&server, now_ms());
    LOG("DPLS CONN %u\n", conn_handle);
}

void dpls_phy6252_disconnected(void)
{
    dpls_server_disconnected(&server, now_ms());
    connection_handle = INVALID_CONNHANDLE;
    connected_at_ms = 0;
    connection_had_encryption = false;
    memset(&rx, 0, sizeof(rx));
    memset(&tx, 0, sizeof(tx));
    if (journal_pending_event_count != 0u) osal_set_event(task_id, DPLS_PHY6252_STORAGE_EVT);
    LOG("DPLS DISC\n");
}

void dpls_phy6252_process_rx(void)
{
    dpls_rx_slot_t *slot;
    if (rx.count == 0u) return;
    slot = &rx.slots[rx.head];
    (void)dpls_server_receive(&server, slot->data, slot->length, now_ms());
    slot->length = 0;
    rx.head = (uint8)((rx.head + 1u) % DPLS_RX_QUEUE_DEPTH);
    --rx.count;
    if (rx.count != 0u) osal_set_event(task_id, DPLS_PHY6252_RX_EVT);
}

static void tick_link_security(uint32_t now)
{
    if (!dpls_phy6252_link_active()) return;
    if (link_encrypted(NULL)) connection_had_encryption = true;
    if (!link_encrypted(NULL) && !connection_had_encryption && connected_at_ms != 0u &&
        (uint32_t)(now - connected_at_ms) >= DPLS_LINK_ENCRYPT_TIMEOUT_MS) {
        LOG("DPLS KILL plaintext timeout\n");
        connected_at_ms = 0;
        /* Timeout limits a leaked ACL only. It is never evidence that stored
         * bonding keys are stale, so do not mutate GAPBondMgr persistence. */
        (void)GAPRole_TerminateConnection();
    }
}

static void tick_factory_reset(uint32_t now)
{
    if (factory_reset_armed) {
        if (!hal_gpio_read(DPLS_FACTORY_RESET_PIN)) factory_reset_armed = false;
        else if ((uint32_t)(now - factory_reset_started_ms) >= DPLS_FACTORY_RESET_HOLD_MS) {
            factory_reset_armed = false;
            clear_settings_and_bonds();
        }
    }
}

static void tick_measurements(void)
{
    if (++adc_decimate >= DPLS_ADC_DECIMATE) {
        adc_decimate = 0;
        adc_pending = dpls_phy6252_link_active()
            ? (uint8_t)DPLS_ADC_NEED_ALL
            : (uint8_t)(DPLS_ADC_NEED_PORT1 | DPLS_ADC_NEED_VCAP);
        adc_kick();
    }
    update_power_state();
}

static void tick_tx(uint32_t now)
{
    if (tx.in_flight && dpls_gatt_needs_confirmation(connection_handle) &&
        (uint32_t)(now - tx.in_flight_since_ms) >= DPLS_TX_CONFIRM_TIMEOUT_MS) {
        LOG("DPLS TX timeout count=%u\n", tx.count);
        /* Timeout is a failure/pipeline recovery event, not ATT confirmation. */
        tx_complete_head();
    }
    dpls_phy6252_process_tx();
}

void dpls_phy6252_tick(void)
{
    uint32_t now = now_ms();
    tick_link_security(now);
    tick_factory_reset(now);
    tick_measurements();
    dpls_server_tick(&server, now);
    tick_tx(now);
    /* No SNV journal write here. Connected ticks are radio-critical; storage
     * is serviced only from DPLS_PHY6252_STORAGE_EVT after disconnect. */
}

uint32 dpls_phy6252_led_tick(void)
{
    uint32_t now = now_ms();
    uint32_t delay;
    dpls_led_scene_t scene;
    bool reserve = power_source(NULL) == DPLS_POWER_RESERVE;
    if (identify_led_active) scene = DPLS_LED_SCENE_IDENTIFY;
    else if (auto_isolation_active) scene = DPLS_LED_SCENE_AUTO_ISOLATION;
    else scene = led_scene_for_mode(server.safety.mode);
    dpls_led_set(&status_led, scene, reserve, now);
    delay = dpls_led_tick(&status_led, now);
    if (delay == 0u) return 0u;
    if (delay < DPLS_LED_TICK_MIN_MS) delay = DPLS_LED_TICK_MIN_MS;
    if (delay > DPLS_LED_TICK_MAX_MS) delay = DPLS_LED_TICK_MAX_MS;
    return delay;
}
