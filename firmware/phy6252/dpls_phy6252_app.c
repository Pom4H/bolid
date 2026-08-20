#include "dpls_phy6252_app.h"

#include "dpls_ble_identity.h"
#include "dpls_board.h"
#include "dpls_calib.h"
#include "dpls_gatt_service.h"
#include "dpls_led.h"
#include "dpls_server.h"
#include "OSAL.h"
#if defined(__GNUC__)
/* adc.h prototypes static helpers that live in adc.c. GCC diagnoses them at
 * end of TU, so a push/pop around the include does not hide -Wunused-function. */
#pragma GCC diagnostic ignored "-Wunused-function"
#endif
#include "adc.h"
#include "error.h"
#include "flash.h"
#include "gpio.h"
#include "linkdb.h"
#include "ll_enc.h"
#include "log.h"
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
#define DPLS_AUTH_LOCK_MAGIC 0x4B434C44u /* "DLCK" */
#define DPLS_SETTINGS_SNV_ID 0x80u
#define DPLS_SETTINGS_STATE_SNV_ID 0x81u
#define DPLS_CALIB_SNV_ID 0x83u /* 0x82 is taken by dpls_ble_identity (BLE MAC) */
#define DPLS_AUTH_LOCK_SNV_ID 0x84u
#define DPLS_JOURNAL_FIRST_SNV_ID 0x90u
#define DPLS_JOURNAL_EVENTS_PER_BLOCK 10u
#define DPLS_JOURNAL_RECORD_SIZE 12u
#define DPLS_JOURNAL_BLOCK_COUNT (DPLS_EVENT_CAPACITY / DPLS_JOURNAL_EVENTS_PER_BLOCK)
#define DPLS_JOURNAL_BLOCK_SIZE (DPLS_JOURNAL_EVENTS_PER_BLOCK * DPLS_JOURNAL_RECORD_SIZE)
#define DPLS_NAME_SIZE 32u
#define DPLS_HW_REVISION 2u /* four independent voltage inputs: +1, +2, +T, reserve */
#define DPLS_SETTINGS_EMPTY_MARKER 0x45u
#define DPLS_SETTINGS_VALID_MARKER 0x56u
#define DPLS_FACTORY_RESET_PIN DPLS_PIN_FACTORY_RESET
#define DPLS_FACTORY_RESET_HOLD_MS 5000u
/* LED re-arm bounds: fine enough for a 150 ms flash edge, cheap in long pauses. */
#define DPLS_LED_TICK_MIN_MS 10u
#define DPLS_LED_TICK_MAX_MS 250u
/* Sampling rides the 1 Hz tick and averages over a window against noise. The ISR
 * only copies raw samples and wakes the task — scaling and calibration must not
 * compete with the radio in interrupt context. */
#define DPLS_ADC_DECIMATE 1u
#define DPLS_ADC_WINDOW 8u
/* One channel per kick. The SDK's ADCC handler drains the IRQ only once every
 * requested channel has finished, so a multi-channel kick can leave an interrupt
 * pending and starve the OSAL loop. See tests/test_adc_irq_model.c. */
#define DPLS_ADC_NEED_PORT1 0x01u
#define DPLS_ADC_NEED_PORT2 0x02u
#define DPLS_ADC_NEED_PORT_T 0x04u
#define DPLS_ADC_NEED_VCAP 0x08u
#define DPLS_ADC_NEED_ALL (DPLS_ADC_NEED_PORT1 | DPLS_ADC_NEED_PORT2 | \
                           DPLS_ADC_NEED_PORT_T | DPLS_ADC_NEED_VCAP)
/* Line present/absent, with hysteresis so a value near the threshold does not
 * flap. Below the 5 V line minimum the device runs from reserve — which is also
 * true while a KZ mode shorts the line. */
#define DPLS_LINE_PRESENT_MV 4000u
#define DPLS_LINE_ABSENT_MV 3000u
/* Usable reserve window ~5.0→3.6 V. Both thresholds depend on the VCAP divider,
 * which the supplied schematic does not give: confirm on hardware. */
#define DPLS_RESERVE_LOW_MV 3700u
#define DPLS_RESERVE_OK_MV 4000u
/* Placeholder until the schematic gives the divider; calibratable like the line. */
#define DPLS_VCAP_NOMINAL_GAIN_MILLI 2000u
/* BRIZ-T auto-isolation trips somewhere in 2.9–3.4 V per the RE. Firmware only
 * observes, indicates and logs; the ≤200 ms interruption is hardware. Detection
 * arms only after a healthy line, so a cold start without one is not a short. */
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

/* Brute-force lock marker: written on the 5th wrong password, cleared when the
 * block expires or on factory reset. */
typedef struct {
    uint32_t magic;
    uint8_t locked;
    uint8_t reserved;
    uint16_t crc;
} dpls_auth_lock_t;

/* Two-point calibration. The VCAP fields are stored now so the record layout
 * stays fixed once reserve monitoring lands. */
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
static bool factory_reset_released;
static uint32_t factory_reset_started_ms;
static uint32_t connected_at_ms;
static bool connection_had_encryption;
static uint8_t pre_auth_disconnect_count;
static uint32_t pre_auth_disconnect_window_ms;
/* The largest request is SETUP: 9 overhead + 5 + 31 name + 16 salt + 32
 * verifier = 93 bytes. */
#define DPLS_RX_QUEUE_DEPTH 6u
#define DPLS_RX_SLOT_SIZE 96u
typedef struct { uint8 data[DPLS_RX_SLOT_SIZE]; uint16 length; } dpls_rx_slot_t;
static dpls_rx_slot_t rx_queue[DPLS_RX_QUEUE_DEPTH];
static uint8 rx_head, rx_tail, rx_count;

/* Indications are paced one-in-flight against the ATT confirmation, otherwise a
 * busy stack drops back-to-back responses. The largest response is a batched
 * LOG_CHUNK (9 + 3 + 15×10 = 162), so slots are sized for that rather than for
 * the 244-byte frame maximum. */
#define DPLS_TX_QUEUE_DEPTH 4u
#define DPLS_TX_SLOT_SIZE 168u
typedef struct { uint16 length; uint8 data[DPLS_TX_SLOT_SIZE]; } dpls_tx_slot_t;
static dpls_tx_slot_t tx_queue[DPLS_TX_QUEUE_DEPTH];
static uint8 tx_head, tx_tail, tx_count;
static bool tx_in_flight;
/* The host occasionally loses a confirmation, and without a deadline the
 * one-in-flight pipeline would wedge behind it forever. On timeout the frame is
 * written off and the pump moves on; the client's next poll refreshes state. */
#define DPLS_TX_CONFIRM_TIMEOUT_MS 2000u
static uint32_t tx_in_flight_since_ms;
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
static volatile uint16_t cached_line_mv;
static volatile uint16_t cached_port2_mv;
static volatile uint16_t cached_port_t_mv;
static volatile uint16_t cached_vcap_mv;
static volatile bool adc_busy;
static uint8_t adc_pending; /* DPLS_ADC_NEED_* bits still owed this cycle */
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
        /* Scanning 20 populated blocks outlasts the 2 s watchdog window, and
         * init runs before the OSAL feed task exists. Feed per block or a full
         * journal makes the device unbootable. */
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

    /* Second pass builds a 25-byte validity bitmap and keeps no event array in
     * RAM. A torn record truncates the recovered history there rather than
     * exporting stale bytes. */
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

/* De-energize every mode output: isolation switches conduct, short shunts open,
 * which is "Norma". Every auto-return path funnels through here. */
static void mode_outputs_off(void)
{
    hal_gpio_write(DPLS_PIN_ISO_1, 0);
    hal_gpio_write(DPLS_PIN_ISO_2, 0);
    hal_gpio_write(DPLS_PIN_ISO_T, 0);
    hal_gpio_write(DPLS_PIN_KZ_1, 0);
    hal_gpio_write(DPLS_PIN_KZ_2, 0);
    hal_gpio_write(DPLS_PIN_KZ_T, 0);
}

/* Sleep is forbidden only while a power stage is asserted: waking reprograms the
 * clocks and the GPIO block underneath an energized output. Norma sleeps freely,
 * connected or not — staying awake for a whole session would blow the 0.5 mA
 * budget. The target layer registers MOD_USR1; without that the lock is a no-op. */
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
    hardware_mode = DPLS_MODE_NORMAL;
    control_sleep_guard(false);
}

#define DPLS_ROM_BOOTINFO_PART_COUNT_ADDR 0x11002000u

bool dpls_phy6252_prepare_rom_boot(void)
{
    uint32 invalid_part_count = 0u;
    uint32 verify = 0xffffffffu;

    safe_normal(NULL);
    hal_gpio_write(DPLS_PIN_LED_RED, 0);
    hal_gpio_write(DPLS_PIN_LED_GREEN, 0);
    hal_gpio_write(DPLS_PIN_LED_BLUE, 0);
    if (hal_flash_write(DPLS_ROM_BOOTINFO_PART_COUNT_ADDR,
                        (uint8 *)&invalid_part_count,
                        sizeof(invalid_part_count)) != PPlus_SUCCESS)
        return false;
    if (hal_flash_read(DPLS_ROM_BOOTINFO_PART_COUNT_ADDR,
                       (uint8 *)&verify, sizeof(verify)) != PPlus_SUCCESS)
        return false;
    return verify == 0u;
}

/* True means "output driven", not "electrically confirmed": the board has no
 * power-stage feedback (capability HW_READBACK is clear), so reports carry the
 * commanded mode. The false path is where a future read-back build would fail. */
static bool apply_mode(void *context, dpls_mode_t mode)
{
    (void)context;
    if (mode > DPLS_MODE_SHORT_T) return false;
    /* Break-before-make: all outputs safe first, then assert the single line. */
    mode_outputs_off();
    switch (mode) {
    case DPLS_MODE_NORMAL: break;
    case DPLS_MODE_OPEN_T:
        hal_gpio_write(DPLS_PIN_ISO_T, 1);
        break;
    case DPLS_MODE_OPEN_MAIN:
        hal_gpio_write(DPLS_PIN_ISO_2, 1);
        break;
    case DPLS_MODE_SHORT_1:
        hal_gpio_write(DPLS_PIN_KZ_1, 1);
        break;
    case DPLS_MODE_SHORT_2:
        hal_gpio_write(DPLS_PIN_KZ_2, 1);
        break;
    case DPLS_MODE_SHORT_T:
        hal_gpio_write(DPLS_PIN_KZ_T, 1);
        break;
    default: return false;
    }
    hardware_mode = mode;
    control_sleep_guard(mode != DPLS_MODE_NORMAL);
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
    /* Scenes are green. Hold the other channels low so a retained RGB state
     * cannot bleed into the colour. */
    hal_gpio_write(DPLS_PIN_LED_RED, 0);
    hal_gpio_write(DPLS_PIN_LED_BLUE, 0);
    hal_gpio_write(DPLS_PIN_LED_GREEN, on ? 1 : 0);
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

/* One shared buffer: channels convert strictly one at a time, so per-input
 * buffers would only waste SRAM. */
static volatile uint16_t adc_raw[MAX_ADC_SAMPLE_SIZE];
static volatile uint8_t adc_raw_size;
static volatile adc_CH_t adc_raw_channel;
static volatile bool adc_raw_ready;

/* Completion ISR: copy the samples, flag the channel, wake the task. Keep float,
 * averaging, flash and BLE out of here — doing that work in interrupt context is
 * the leading suspect for the ADC/radio watchdog reset loop. */
static void adc_evt(adc_Evt_t *event)
{
    uint8_t i, n;
    if (event->type != HAL_ADC_EVT_DATA) {
        adc_busy = false;
        return;
    }
    n = event->size > MAX_ADC_SAMPLE_SIZE ? MAX_ADC_SAMPLE_SIZE : event->size;
    for (i = 0; i < n; ++i) adc_raw[i] = event->data[i];
    adc_raw_size = n;
    adc_raw_channel = event->ch;
    adc_raw_ready = true;
    /* One-shot: the IRQ handler stops the converter itself, so only the
     * re-entrancy guard is ours to clear. */
    adc_busy = false;
    osal_set_event(task_id, DPLS_PHY6252_ADC_EVT);
}

/* Task context: raw samples, pin volts, calibration, moving average. */
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
    /* Standard resolution: every divider keeps its pin at or below ~1 V. */
    if (adc_pending & DPLS_ADC_NEED_PORT1) {
        channel = ADC_BIT(DPLS_ADC_CHANNEL(DPLS_PIN_PORT1_ADC));
        claim = DPLS_ADC_NEED_PORT1;
    } else if (adc_pending & DPLS_ADC_NEED_PORT2) {
        channel = ADC_BIT(DPLS_ADC_CHANNEL(DPLS_PIN_PORT2_ADC));
        claim = DPLS_ADC_NEED_PORT2;
    } else if (adc_pending & DPLS_ADC_NEED_PORT_T) {
        channel = ADC_BIT(DPLS_ADC_CHANNEL(DPLS_PIN_PORT_T_ADC));
        claim = DPLS_ADC_NEED_PORT_T;
    } else {
        channel = ADC_BIT(DPLS_ADC_CHANNEL(DPLS_PIN_VCAP_ADC));
        claim = DPLS_ADC_NEED_VCAP;
    }
    cfg.channel = channel;
    cfg.is_continue_mode = FALSE;
    cfg.is_differential_mode = 0u;
    cfg.is_high_resolution = 0u;
    adc_busy = true;
    if (hal_adc_config_channel(cfg, adc_evt) != PPlus_SUCCESS) {
        adc_busy = false;
        return;
    }
    if (hal_adc_start(INTERRUPT_MODE) != PPlus_SUCCESS) {
        (void)hal_adc_stop();
        adc_busy = false;
        return;
    }
    adc_pending = (uint8_t)(adc_pending & (uint8_t)~claim);
}

void dpls_phy6252_process_adc(void)
{
    if (adc_raw_ready) {
        adc_CH_t ch = adc_raw_channel;
        uint8_t size = adc_raw_size;
        adc_raw_ready = false;
        switch (ch) {
        case DPLS_ADC_CHANNEL(DPLS_PIN_PORT1_ADC):
            process_adc_channel(ch, adc_raw, size, &line_calib,
                                line_window, &line_window_count, &line_window_pos,
                                &cached_line_mv);
            break;
        case DPLS_ADC_CHANNEL(DPLS_PIN_PORT2_ADC):
            process_adc_channel(ch, adc_raw, size, &line_calib,
                                port2_window, &port2_window_count, &port2_window_pos,
                                &cached_port2_mv);
            break;
        case DPLS_ADC_CHANNEL(DPLS_PIN_PORT_T_ADC):
            process_adc_channel(ch, adc_raw, size, &line_calib,
                                port_t_window, &port_t_window_count, &port_t_window_pos,
                                &cached_port_t_mv);
            break;
        case DPLS_ADC_CHANNEL(DPLS_PIN_VCAP_ADC):
            process_adc_channel(ch, adc_raw, size, &vcap_calib,
                                vcap_window, &vcap_window_count, &vcap_window_pos,
                                &cached_vcap_mv);
            break;
        default:
            break;
        }
    }
    /* Start the next channel from task context after consuming the shared raw
     * buffer. A complete four-channel cycle is still initiated every second. */
    adc_kick();
}

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
        /* Line voltage alone cannot distinguish a real downstream short from a
         * normal transition to reserve. In particular, latching isolation after
         * power_state changed to RESERVE masks the reserve LED with a permanent
         * green AUTO_ISOLATION scene. Until hardware provides an independent
         * isolation/readback signal, never infer it while the line is absent. */
        if (line > DPLS_LINE_PRESENT_MV) line_established = true;
        if (power_state == DPLS_POWER_RESERVE) {
            auto_isolation_active = false;
        } else if (line_established && hardware_mode == DPLS_MODE_NORMAL) {
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

static uint16_t port1_voltage_mv(void *context)
{
    (void)context;
    return cached_line_mv;
}

static uint16_t port2_voltage_mv(void *context)
{
    (void)context;
    return cached_port2_mv;
}

static uint16_t port_t_voltage_mv(void *context)
{
    (void)context;
    return cached_port_t_mv;
}

static uint16_t reserve_voltage_mv(void *context)
{
    (void)context;
    return cached_vcap_mv;
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

/* Which STATE_REPORT fields carry a real measurement. Line-derived fields
 * (voltage, power source, auto-isolation) become valid once the line channel
 * has produced a sample; reserve once the VCAP channel has. Until the first
 * conversion lands the mask stays 0 and the app shows "—" / "Не определён"
 * instead of the cold-cache 0 mV / "from line" defaults. */
static uint8_t measurement_validity(void *context)
{
    uint8_t flags = 0;
    (void)context;
    if (line_window_count != 0u)
        flags |= DPLS_STATE_PORT_1_VALID | DPLS_STATE_POWER_VALID |
                 DPLS_STATE_AUTOISO_VALID;
    if (port2_window_count != 0u)
        flags |= DPLS_STATE_PORT_2_VALID;
    if (port_t_window_count != 0u)
        flags |= DPLS_STATE_PORT_T_VALID;
    if (vcap_window_count != 0u)
        flags |= DPLS_STATE_RESERVE_VOLTAGE_VALID;
    if (line_calib_from_nv)
        flags |= DPLS_STATE_ADC_CALIBRATED;
    return flags;
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

/* Persist the current in-RAM `settings` record (magic + CRC), read it back to
 * confirm the write, then set the VALID marker. Shared by initial commissioning
 * and the in-place name/password updates. */
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

/* NAME_SET: replace just the user name, keep salt/verifier. */
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

/* PASSWORD_SET: replace just salt+verifier, keep the name. */
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
    out->capabilities = 0u;
    out->capabilities |= DPLS_CAP_ADC_PRESENT;
    out->capabilities |= DPLS_CAP_MULTI_VOLTAGE_REPORT;
    if (line_calib_from_nv) out->capabilities |= DPLS_CAP_ADC_CALIBRATED;
    /* DPLS_CAP_HW_READBACK stays clear: no power-stage feedback yet (stage 6). */
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
    dpls_auth_lock_t current;
    dpls_auth_lock_t record;
    bool current_valid;
    (void)context;

    current_valid = osal_snv_read(DPLS_AUTH_LOCK_SNV_ID, sizeof(current), &current) == SUCCESS &&
                    current.magic == DPLS_AUTH_LOCK_MAGIC &&
                    current.crc == dpls_crc16((const uint8_t *)&current, offsetof(dpls_auth_lock_t, crc));
    if (current_valid && ((current.locked != 0u) == locked)) return true;
    /* A missing or corrupt marker already means "not persistently locked", so
     * writing the default state would burn a flash erase/write cycle after every
     * successful authentication for nothing. */
    if (!current_valid && !locked) return true;

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

/* Send the head of the TX queue if nothing is in flight. Runs from the OSAL
 * task (via DPLS_PHY6252_TX_EVT or the tick), never nested under the RX handler,
 * so the ATT indication buffer stays off the receive path's stack. */
static void tx_pump(void)
{
    bStatus_t rc;
    if (tx_in_flight || tx_count == 0u || connection_handle == INVALID_CONNHANDLE) return;
    rc = dpls_gatt_send_indication(connection_handle, tx_queue[tx_head].data,
                                   tx_queue[tx_head].length, task_id);
    if (rc == SUCCESS) {
        tx_in_flight = true; /* wait for the ATT confirmation before the next */
        tx_in_flight_since_ms = now_ms();
    } else if (rc == bleMemAllocError || rc == blePending) {
        /* Transient: keep the head and retry from the next tick. */
    } else {
        /* Permanent (not subscribed / too big for MTU / not connected): drop
         * this frame so the queue cannot deadlock behind an unsendable head. */
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
        /* Queue full: the client has stopped confirming. Fail safe rather than
         * lose a control response — drop to Norma, reset the queue and drop the
         * link so the reconnect starts clean. */
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
    /* Full queue: NAK the write so the client retries instead of losing the
     * frame silently. */
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
    /* The explicit marker is written only by the physical reset path. A bad
     * settings record never becomes remotely commissionable by accident. */
    (void)osal_snv_write(DPLS_SETTINGS_SNV_ID, sizeof(settings), &settings);
    (void)osal_snv_write(DPLS_SETTINGS_STATE_SNV_ID, sizeof(marker), &marker);
    /* Factory reset also lifts any persisted brute-force lock. */
    (void)auth_lock_write(NULL, false);
    settings_state = DPLS_SETTINGS_EMPTY;
    GAPBondMgr_SetParameter(GAPBOND_ERASE_ALLBONDS, 0, NULL);
    dpls_ble_identity_reset_bonding_keys();
    hal_gpio_write(DPLS_PIN_LED_GREEN, 1);
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
    /* Drive every mode output to the safe "Norma" level before the radio or the
     * state machine can touch them. hal_gpio_write() is the only glitch-safe
     * primitive here: it loads the data latch and only then enables the output
     * direction. Enabling the direction first — hal_gpio_pin_init(pin, OEN) —
     * would assert a pulse on an active-high control pin whenever a retained or
     * warm-boot latch still holds 1. */
    hal_gpio_write(DPLS_PIN_ISO_1, 0);
    hal_gpio_write(DPLS_PIN_ISO_2, 0);
    hal_gpio_write(DPLS_PIN_ISO_T, 0);
    hal_gpio_write(DPLS_PIN_KZ_1, 0);
    hal_gpio_write(DPLS_PIN_KZ_2, 0);
    hal_gpio_write(DPLS_PIN_KZ_T, 0);
    hal_gpio_write(DPLS_PIN_LED_RED, 0);
    hal_gpio_write(DPLS_PIN_LED_GREEN, 0);
    hal_gpio_write(DPLS_PIN_LED_BLUE, 0);
    /* PHY62xx sleep powers the GPIO block down: an output pad holds its level
     * through sleep only while it is registered for AON retention, and the
     * wake handler restores just those pins. Without this a mode output goes
     * high and then silently collapses at the first sleep window — the pin is
     * written once on the mode change and never again, so it never comes back
     * (the LED only survived because its blink rewrites the pad). */
    (void)hal_gpioretention_register(DPLS_PIN_ISO_1);
    (void)hal_gpioretention_register(DPLS_PIN_ISO_2);
    (void)hal_gpioretention_register(DPLS_PIN_ISO_T);
    (void)hal_gpioretention_register(DPLS_PIN_KZ_1);
    (void)hal_gpioretention_register(DPLS_PIN_KZ_2);
    (void)hal_gpioretention_register(DPLS_PIN_KZ_T);
    (void)hal_gpioretention_register(DPLS_PIN_LED_RED);
    (void)hal_gpioretention_register(DPLS_PIN_LED_GREEN);
    (void)hal_gpioretention_register(DPLS_PIN_LED_BLUE);
    hardware_mode = DPLS_MODE_NORMAL;
    dpls_led_init(&status_led, status_led_output, NULL, now_ms());
    line_window_count = line_window_pos = 0;
    port2_window_count = port2_window_pos = 0;
    port_t_window_count = port_t_window_pos = 0;
    vcap_window_count = vcap_window_pos = adc_decimate = 0;
    cached_line_mv = cached_port2_mv = cached_port_t_mv = cached_vcap_mv = 0;
    adc_pending = 0u;
    adc_raw_ready = false;
    power_state = DPLS_POWER_LINE;
    reserve_low_state = false;
    auto_isolation_active = false;
    line_established = false;
    adc_busy = false;
    load_calibration();
    hal_adc_init();
    hal_gpio_pin_init(DPLS_FACTORY_RESET_PIN, IE);
    hal_gpio_pull_set(DPLS_FACTORY_RESET_PIN, GPIO_PULL_DOWN);
    classify_settings();
    /* A held-high or externally driven P34 at boot must not turn into an
     * endless factory-reset loop. Require one observed release before a
     * subsequent press can start the deliberate five-second gesture. */
    factory_reset_armed = false;
    factory_reset_released = !hal_gpio_read(DPLS_FACTORY_RESET_PIN);
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
    /* An idle connection is allowed to sleep: the guard belongs to the power
     * stage, not to the link. See control_sleep_guard(). */
    dpls_server_connected(&server, now_ms());
}

void dpls_phy6252_disconnected(void)
{
    /* No unlock here: dpls_server_disconnected() forces Norma, and that path
     * owns the guard. */
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
    if (!factory_reset_released) {
        if (!hal_gpio_read(DPLS_FACTORY_RESET_PIN)) factory_reset_released = true;
    } else if (!factory_reset_armed) {
        if (hal_gpio_read(DPLS_FACTORY_RESET_PIN)) {
            factory_reset_armed = true;
            factory_reset_started_ms = now_ms();
        }
    } else if (!hal_gpio_read(DPLS_FACTORY_RESET_PIN)) {
        factory_reset_armed = false;
    } else if ((uint32_t)(now_ms() - factory_reset_started_ms) >= DPLS_FACTORY_RESET_HOLD_MS) {
        factory_reset_armed = false;
        clear_settings_and_bonds();
    }
    if (++adc_decimate >= DPLS_ADC_DECIMATE) {
        adc_decimate = 0;
        adc_pending = connection_handle != INVALID_CONNHANDLE
            ? (uint8_t)DPLS_ADC_NEED_ALL
            : (uint8_t)(DPLS_ADC_NEED_PORT1 | DPLS_ADC_NEED_VCAP);
        adc_kick();
    }
    update_power_state();
#if defined(DPLS_HW_DIAGNOSTICS)
    LOG("[DPLS PWR] line=%u vcap=%u source=%u low=%u autoiso=%u mode=%u\n",
        cached_line_mv, cached_vcap_mv, (unsigned)power_state,
        reserve_low_state ? 1u : 0u, auto_isolation_active ? 1u : 0u,
        (unsigned)hardware_mode);
#endif
    dpls_server_tick(&server, now_ms());
    /* Recover from a lost ATT confirmation: drop the unacknowledged head so the
     * response pipeline cannot stay wedged behind it (see tx_in_flight_since_ms). */
    if (tx_in_flight &&
        (uint32_t)(now_ms() - tx_in_flight_since_ms) >= DPLS_TX_CONFIRM_TIMEOUT_MS) {
        dpls_phy6252_tx_confirmed();
        return;
    }
    /* Retry a TX head that hit a transient stack-busy error on a prior attempt. */
    tx_pump();
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
    /* Pass a zero straight through: it is the "nothing to show" answer, and
     * clamping it to the minimum would keep the core waking up in Norma. */
    if (delay == 0u) return 0u;
    if (delay < DPLS_LED_TICK_MIN_MS) delay = DPLS_LED_TICK_MIN_MS;
    if (delay > DPLS_LED_TICK_MAX_MS) delay = DPLS_LED_TICK_MAX_MS;
    return delay;
}
