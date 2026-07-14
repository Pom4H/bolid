#include "dpls_phy6252_app.h"

#include "dpls_ble_identity.h"
#include "dpls_board.h"
#include "dpls_calib.h"
#include "dpls_gatt_service.h"
#include "dpls_led.h"
#include "dpls_server.h"
#include "OSAL.h"
#include "OSAL_Timers.h"
#include "adc.h"
#include "error.h"
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
#define DPLS_CALIB_MAGIC 0x434C5044u
#define DPLS_SETTINGS_SNV_ID 0x80u
#define DPLS_SETTINGS_STATE_SNV_ID 0x81u
#define DPLS_CALIB_SNV_ID 0x83u /* 0x82 is taken by dpls_ble_identity (BLE MAC) */
#define DPLS_JOURNAL_FIRST_SNV_ID 0x90u
#define DPLS_JOURNAL_EVENTS_PER_BLOCK 10u
#define DPLS_JOURNAL_RECORD_SIZE 12u
#define DPLS_JOURNAL_BLOCK_COUNT (DPLS_EVENT_CAPACITY / DPLS_JOURNAL_EVENTS_PER_BLOCK)
#define DPLS_JOURNAL_BLOCK_SIZE (DPLS_JOURNAL_EVENTS_PER_BLOCK * DPLS_JOURNAL_RECORD_SIZE)
#define DPLS_NAME_SIZE 32u
#define DPLS_SETTINGS_EMPTY_MARKER 0x45u
#define DPLS_SETTINGS_VALID_MARKER 0x56u
#define DPLS_FACTORY_RESET_PIN DPLS_PIN_FACTORY_RESET
#define DPLS_FACTORY_RESET_HOLD_MS 5000u
/* Bound the LED re-arming interval: fine enough for a 150 ms flash edge, but
 * still cheap during the long inter-series pauses. */
#define DPLS_LED_TICK_MIN_MS 10u
#define DPLS_LED_TICK_MAX_MS 250u
/* Line-voltage ADC: sample about once a second (every Nth 200 ms tick) and
 * average over a short window against noise, keeping the pulsed draw within the
 * ≤0.5 mA budget. */
#define DPLS_ADC_DECIMATE 5u
#define DPLS_ADC_WINDOW 8u
/* DIAGNOSTIC: ADC sampling on the kit triggers a watchdog reset loop (radio/
 * clock coexistence). Disabled to isolate; re-enable once ADC↔BLE is sorted. */
#define DPLS_ADC_SAMPLING 0
/* Power-source detection from the line voltage, with hysteresis so a value near
 * the threshold does not flap. Below the 5 V line minimum the device is running
 * from its reserve (also true while it is shorting the line in a KZ mode). */
#define DPLS_LINE_PRESENT_MV 4000u
#define DPLS_LINE_ABSENT_MV 3000u
/* Reserve super-capacitor low/clear thresholds (usable window ~5.0→3.6 V).
 * These depend on the VCAP divider, which is not in the supplied schematic —
 * confirm and calibrate on hardware. */
#define DPLS_RESERVE_LOW_MV 3700u
#define DPLS_RESERVE_OK_MV 4000u
/* Placeholder VCAP divider until the schematic value is known; calibratable via
 * the same NVM block as the line channel. */
#define DPLS_VCAP_NOMINAL_GAIN_MILLI 2000u
/* Real short-circuit (BRIZ-T auto-isolation): the RE gives a 2.9–3.4 V trip
 * band. Firmware only observes, indicates and logs — the actual ≤200 ms current
 * interruption is done in hardware. Detection is armed only after the line has
 * been seen healthy, so powering up with no line does not look like a short. */
#define DPLS_AUTOISO_TRIP_MV 3000u
#define DPLS_AUTOISO_CLEAR_MV 4500u
/* Drop stale NV bonds after repeated encrypted links that never reach DPLS auth. */
#define DPLS_BOND_DESYNC_LIMIT 3u
#define DPLS_BOND_DESYNC_WINDOW_MS 120000u
/* Plaintext link timeout — pairing never completed. */
#define DPLS_LINK_ENCRYPT_TIMEOUT_MS 15000u

typedef struct {
    uint32_t magic;
    char name[DPLS_NAME_SIZE];
    uint8_t salt[DPLS_AUTH_SALT_SIZE];
    uint8_t verifier[DPLS_AUTH_PROOF_SIZE];
    uint16_t crc;
} dpls_settings_t;

/* Persisted two-point calibration. The reserve (VCAP) fields are reserved for
 * the reserve-monitoring stage and stored now so the layout stays fixed. */
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

static dpls_calib_t line_calib;
static dpls_calib_t vcap_calib;
static uint16_t line_window[DPLS_ADC_WINDOW];
static uint16_t vcap_window[DPLS_ADC_WINDOW];
static uint8_t line_window_count, line_window_pos;
static uint8_t vcap_window_count, vcap_window_pos;
static volatile uint16_t cached_line_mv;
static volatile uint16_t cached_vcap_mv;
static volatile bool adc_busy;
static uint8_t adc_decimate;
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

/* Drop every mode output to its safe (de-energized) level. Isolation switches
 * conduct and short shunts open, which is the "Norma" state. */
static void mode_outputs_off(void)
{
    hal_gpio_write(DPLS_PIN_ISO_1, 0);
    hal_gpio_write(DPLS_PIN_ISO_2, 0);
    hal_gpio_write(DPLS_PIN_ISO_T, 0);
    hal_gpio_write(DPLS_PIN_KZ_1, 0);
    hal_gpio_write(DPLS_PIN_KZ_2, 0);
    hal_gpio_write(DPLS_PIN_KZ_T, 0);
}

static void safe_normal(void *context)
{
    (void)context;
    mode_outputs_off();
    hardware_mode = DPLS_MODE_NORMAL;
}

static bool apply_mode(void *context, dpls_mode_t mode)
{
    (void)context;
    if (mode > DPLS_MODE_SHORT_T) return false;
    /* Break-before-make: return to the all-safe state first so no two outputs
     * are ever driven together, then assert the single line for this mode. */
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
    hardware_mode = mode;
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
    hal_gpio_write(DPLS_PIN_STATUS_LED, on ? 1 : 0);
}

static void load_calibration(void)
{
    dpls_calib_nv_t nv;
    dpls_calib_default(&line_calib);
    dpls_calib_default(&vcap_calib);
    vcap_calib.gain_milli = DPLS_VCAP_NOMINAL_GAIN_MILLI;
    if (osal_snv_read(DPLS_CALIB_SNV_ID, sizeof(nv), &nv) == SUCCESS &&
        nv.magic == DPLS_CALIB_MAGIC &&
        nv.crc == dpls_crc16((const uint8_t *)&nv, offsetof(dpls_calib_nv_t, crc))) {
        dpls_calib_t line = {nv.line_gain_milli, nv.line_offset_mv};
        dpls_calib_t vcap = {nv.vcap_gain_milli, nv.vcap_offset_mv};
        if (dpls_calib_valid(&line)) line_calib = line;
        if (dpls_calib_valid(&vcap)) vcap_calib = vcap;
    }
}

#if DPLS_ADC_SAMPLING
/* Fold one sample into a moving-average window and return the current average. */
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

/* ADC completion runs in interrupt context: fold the freshly measured pin
 * voltage into the per-channel window and publish the calibrated value. Both
 * the line (P20/CH9) and reserve (P23/CH1) channels arrive here. */
static void adc_evt(adc_Evt_t *event)
{
    float pin_volts;
    uint32_t pin_mv;
    if (event->type != HAL_ADC_EVT_DATA) {
        adc_busy = false;
        return;
    }
    if (event->ch == ADC_CH9) {
        pin_volts = hal_adc_value_cal(ADC_CH9, event->data, event->size, FALSE, FALSE);
        pin_mv = pin_volts <= 0.0f ? 0u : (uint32_t)(pin_volts * 1000.0f + 0.5f);
        cached_line_mv = fold_window(line_window, &line_window_count, &line_window_pos,
                                     dpls_calib_apply(&line_calib, pin_mv));
    } else if (event->ch == ADC_CH1) {
        pin_volts = hal_adc_value_cal(ADC_CH1, event->data, event->size, FALSE, FALSE);
        pin_mv = pin_volts <= 0.0f ? 0u : (uint32_t)(pin_volts * 1000.0f + 0.5f);
        cached_vcap_mv = fold_window(vcap_window, &vcap_window_count, &vcap_window_pos,
                                     dpls_calib_apply(&vcap_calib, pin_mv));
    }
    /* One-shot mode: the ADC IRQ handler stops the converter after the last
     * channel callback returns, so we only clear our own re-entrancy guard. */
    adc_busy = false;
}

static void adc_kick(void)
{
    adc_Cfg_t cfg;
    if (adc_busy) return;
    memset(&cfg, 0, sizeof(cfg));
    /* Single-ended P20 (line) and P23 (reserve), standard resolution: the
     * high-resolution path tops out near 0.8 V, but the dividers keep both pins
     * near or below ~1 V at full scale. */
    cfg.channel = ADC_BIT(ADC_CH3P_P20) | ADC_BIT(ADC_CH1P_P23);
    cfg.is_continue_mode = FALSE;
    cfg.is_differential_mode = 0u;
    cfg.is_high_resolution = 0u;
    adc_busy = true;
    if (hal_adc_config_channel(cfg, adc_evt) != PPlus_SUCCESS) {
        adc_busy = false;
        return;
    }
    hal_adc_start();
}
#endif /* DPLS_ADC_SAMPLING */

/* Derive the power-source and reserve-low flags with hysteresis. Skipped until
 * the first real sample of each channel so a cold cache of 0 mV cannot spoof a
 * reserve/low-battery state at boot. */
static void update_power_state(void)
{
    if (line_window_count != 0u) {
        uint16_t line = cached_line_mv;
        if (power_state == DPLS_POWER_LINE && line < DPLS_LINE_ABSENT_MV)
            power_state = DPLS_POWER_RESERVE;
        else if (power_state == DPLS_POWER_RESERVE && line > DPLS_LINE_PRESENT_MV)
            power_state = DPLS_POWER_LINE;
        /* A real downstream short is a healthy line collapsing; ignore sags in
         * the KZ test modes (we cause those ourselves). */
        if (line > DPLS_LINE_PRESENT_MV) line_established = true;
        if (line_established && hardware_mode == DPLS_MODE_NORMAL) {
            if (!auto_isolation_active && line < DPLS_AUTOISO_TRIP_MV) auto_isolation_active = true;
            else if (auto_isolation_active && line > DPLS_AUTOISO_CLEAR_MV) auto_isolation_active = false;
        }
    }
    if (vcap_window_count != 0u) {
        uint16_t vcap = cached_vcap_mv;
        if (!reserve_low_state && vcap < DPLS_RESERVE_LOW_MV) reserve_low_state = true;
        else if (reserve_low_state && vcap > DPLS_RESERVE_OK_MV) reserve_low_state = false;
    }
}

static uint16_t voltage_mv(void *context)
{
    (void)context;
    return cached_line_mv;
}

static dpls_power_t power_source(void *context)
{
    (void)context;
    return power_state;
}

static bool reserve_low(void *context)
{
    (void)context;
    return reserve_low_state;
}

static bool real_short_active(void *context)
{
    (void)context;
    return auto_isolation_active;
}

static void identify_led(void *context, bool enabled)
{
    (void)context;
    /* The LED driver renders the 1 Hz identify blink; here we only latch that
     * identify is the active scene so the next LED tick picks it up. */
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
    hal_gpio_write(DPLS_PIN_STATUS_LED, 1);
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
    identify_led_active = false;
    /* Drive every mode output to the safe "Norma" level before the radio or
     * the state machine can touch them. */
    hal_gpio_pin_init(DPLS_PIN_ISO_1, OEN);
    hal_gpio_pin_init(DPLS_PIN_ISO_2, OEN);
    hal_gpio_pin_init(DPLS_PIN_ISO_T, OEN);
    hal_gpio_pin_init(DPLS_PIN_KZ_1, OEN);
    hal_gpio_pin_init(DPLS_PIN_KZ_2, OEN);
    hal_gpio_pin_init(DPLS_PIN_KZ_T, OEN);
    hal_gpio_pin_init(DPLS_PIN_STATUS_LED, OEN);
    mode_outputs_off();
    hal_gpio_write(DPLS_PIN_STATUS_LED, 0);
    hardware_mode = DPLS_MODE_NORMAL;
    dpls_led_init(&status_led, status_led_output, NULL, now_ms());
    line_window_count = line_window_pos = 0;
    vcap_window_count = vcap_window_pos = adc_decimate = 0;
    cached_line_mv = cached_vcap_mv = 0;
    power_state = DPLS_POWER_LINE;
    reserve_low_state = false;
    auto_isolation_active = false;
    line_established = false;
    adc_busy = false;
    load_calibration();
#if DPLS_ADC_SAMPLING
    hal_adc_init();
#endif
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
    hal.power_source = power_source;
    hal.reserve_low = reserve_low;
    hal.real_short_active = real_short_active;
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
#if DPLS_ADC_SAMPLING
    if (++adc_decimate >= DPLS_ADC_DECIMATE) {
        adc_decimate = 0;
        adc_kick();
    }
#endif
    update_power_state();
    dpls_server_tick(&server, now_ms());
}

uint32 dpls_phy6252_led_tick(void)
{
    uint32_t now = now_ms();
    uint32_t delay;
    dpls_led_scene_t scene;
    bool reserve = power_source(NULL) == DPLS_POWER_RESERVE;
    if (identify_led_active) scene = DPLS_LED_SCENE_IDENTIFY;
    else if (auto_isolation_active) scene = DPLS_LED_SCENE_AUTO_ISOLATION;
    else scene = led_scene_for_mode(hardware_mode);
    dpls_led_set(&status_led, scene, reserve, now);
    delay = dpls_led_tick(&status_led, now);
    if (delay < DPLS_LED_TICK_MIN_MS) delay = DPLS_LED_TICK_MIN_MS;
    if (delay > DPLS_LED_TICK_MAX_MS) delay = DPLS_LED_TICK_MAX_MS;
    return delay;
}
