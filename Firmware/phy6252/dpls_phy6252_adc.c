#include "dpls_phy6252_adc.h"

#include "dpls_adc_math.h"
#include "dpls_board.h"
#include "dpls_calib.h"
#include "dpls_protocol.h"
#include "dpls_server.h"
#include "OSAL.h"
#include "adc.h"
#include "error.h"
#include "osal_snv.h"
#include "pwrmgr.h"

#include <stddef.h>
#include <string.h>

#define DPLS_ADC_CALIB_SNV_ID 0x83u /* 0x82 is BLE MAC */
#define DPLS_ADC_CALIB_MAGIC_V1 0x434C5044u
#define DPLS_ADC_CALIB_MAGIC_V2 0x324C5044u /* "DPL2" little-endian */
#define DPLS_ADC_CALIB_V2_VERSION 2u
#define DPLS_ADC_HW_CALIBRATION_WORD 0x11001000u

/* One complete scan starts roughly once per second. The same 1 Hz housekeeping
 * tick also owns lost-IRQ recovery so a watchdog costs no extra wakeups. */
#define DPLS_ADC_PERIOD_MS 1000u
#define DPLS_ADC_CONVERSION_TIMEOUT_MS 1000u
#define DPLS_ADC_STALE_MS 3500u

#define DPLS_ADC_NEED_PORT1 0x01u
#define DPLS_ADC_NEED_PORT2 0x02u
#define DPLS_ADC_NEED_PORT_T 0x04u
#define DPLS_ADC_NEED_VCAP 0x08u
#define DPLS_ADC_NEED_ALL (DPLS_ADC_NEED_PORT1 | DPLS_ADC_NEED_PORT2 | \
                           DPLS_ADC_NEED_PORT_T | DPLS_ADC_NEED_VCAP)
#define DPLS_ADC_NEED_SAFETY (DPLS_ADC_NEED_PORT1 | DPLS_ADC_NEED_VCAP)
#define DPLS_ADC_INDEX_NONE 0xffu
#define DPLS_ADC_CHANNEL_COUNT 4u

#define DPLS_VCAP_NOMINAL_GAIN_MILLI 2000u
#define DPLS_VCAP_GAIN_MIN_MILLI 1000u
#define DPLS_VCAP_GAIN_MAX_MILLI 5000u
#define DPLS_VCAP_OFFSET_LIMIT_MV 2000

extern const unsigned int adc_Lambda[ADC_CH_NUM];

typedef struct {
    uint32_t magic;
    uint32_t line_gain_milli;
    int32_t line_offset_mv;
    uint32_t vcap_gain_milli;
    int32_t vcap_offset_mv;
    uint16_t crc;
} dpls_calib_nv_v1_t;

#define DPLS_CALIB_V2_DATA_SIZE 40u
#define DPLS_CALIB_V2_SIZE 42u

typedef char dpls_adc_assert_port1[(DPLS_PIN_PORT1_ADC == GPIO_P20) ? 1 : -1];
typedef char dpls_adc_assert_port2[(DPLS_PIN_PORT2_ADC == GPIO_P15) ? 1 : -1];
typedef char dpls_adc_assert_port_t[(DPLS_PIN_PORT_T_ADC == GPIO_P24) ? 1 : -1];
typedef char dpls_adc_assert_vcap[(DPLS_PIN_VCAP_ADC == GPIO_P23) ? 1 : -1];

typedef struct {
    uint8_t pending_bit;
    uint8_t config_channel;
    adc_CH_t result_channel;
    uint8_t validity_bit;
    dpls_calib_t calibration;
    volatile uint16_t cached_mv;
    uint32_t last_sample_ms;
} dpls_adc_channel_t;

static dpls_adc_channel_t channels[DPLS_ADC_CHANNEL_COUNT];
static uint8_t calibrated_mask;
static uint8_t task_id;
static uint16_t process_event;
static bool initialized;
static bool radio_gated;
static volatile bool adc_busy;
static uint8_t adc_pending;
static uint8_t scan_mask = DPLS_ADC_NEED_SAFETY;
static uint8_t inflight_index = DPLS_ADC_INDEX_NONE;
static uint32_t inflight_started_ms;
static uint32_t next_cycle_ms;

static volatile uint16_t adc_raw[MAX_ADC_SAMPLE_SIZE];
static volatile uint8_t adc_raw_size;
static volatile adc_CH_t adc_raw_channel;
static volatile bool adc_raw_ready;
static volatile bool adc_event_failed;
static uint16_t adc_hw_calibration_negative;
static uint16_t adc_hw_calibration_positive;

static bool elapsed(uint32_t now, uint32_t deadline)
{
    return (int32_t)(now - deadline) >= 0;
}

static uint16_t rd16(const uint8_t *p)
{
    return (uint16_t)(p[0] | ((uint16_t)p[1] << 8));
}

static uint32_t rd32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void load_hw_adc_calibration(void)
{
    const volatile uint32_t *word = (const volatile uint32_t *)(uintptr_t)DPLS_ADC_HW_CALIBRATION_WORD;
    uint32_t raw = *word;
    adc_hw_calibration_negative = (uint16_t)(raw & 0x0fffu);
    adc_hw_calibration_positive = (uint16_t)((raw >> 16) & 0x0fffu);
}

static void set_default_calibration(void)
{
    uint8_t i;
    for (i = 0; i < 3u; ++i)
        dpls_calib_default(&channels[i].calibration);
    dpls_calib_default(&channels[3].calibration);
    channels[3].calibration.gain_milli = DPLS_VCAP_NOMINAL_GAIN_MILLI;
    channels[3].calibration.offset_mv = 0;
    calibrated_mask = 0u;
}

static bool reserve_calib_valid(const dpls_calib_t *cal)
{
    return dpls_calib_valid_range(cal,
                                  DPLS_VCAP_GAIN_MIN_MILLI,
                                  DPLS_VCAP_GAIN_MAX_MILLI,
                                  DPLS_VCAP_OFFSET_LIMIT_MV);
}

static bool load_calibration_v2(void)
{
    uint8_t raw[DPLS_CALIB_V2_SIZE];
    dpls_calib_t parsed[DPLS_ADC_CHANNEL_COUNT];
    uint8_t i;

    if (osal_snv_read(DPLS_ADC_CALIB_SNV_ID, sizeof(raw), raw) != SUCCESS)
        return false;
    if (rd32(raw) != DPLS_ADC_CALIB_MAGIC_V2 || raw[4] != DPLS_ADC_CALIB_V2_VERSION ||
        rd16(raw + DPLS_CALIB_V2_DATA_SIZE) != dpls_crc16(raw, DPLS_CALIB_V2_DATA_SIZE))
        return false;

    for (i = 0; i < DPLS_ADC_CHANNEL_COUNT; ++i) {
        const uint8_t *record = raw + 8u + (uint8_t)(i * 8u);
        parsed[i].gain_milli = rd32(record);
        parsed[i].offset_mv = (int32_t)rd32(record + 4u);
    }
    if (!dpls_calib_valid(&parsed[0]) || !dpls_calib_valid(&parsed[1]) ||
        !dpls_calib_valid(&parsed[2]) || !reserve_calib_valid(&parsed[3]))
        return false;

    for (i = 0; i < DPLS_ADC_CHANNEL_COUNT; ++i)
        channels[i].calibration = parsed[i];
    calibrated_mask = DPLS_ADC_NEED_ALL;
    return true;
}

static void load_legacy_calibration(void)
{
    dpls_calib_nv_v1_t nv;
    if (osal_snv_read(DPLS_ADC_CALIB_SNV_ID, sizeof(nv), &nv) != SUCCESS ||
        nv.magic != DPLS_ADC_CALIB_MAGIC_V1 ||
        nv.crc != dpls_crc16((const uint8_t *)&nv, offsetof(dpls_calib_nv_v1_t, crc)))
        return;

    {
        dpls_calib_t line = {nv.line_gain_milli, nv.line_offset_mv};
        if (dpls_calib_valid(&line)) {
            channels[0].calibration = line;
            calibrated_mask |= DPLS_ADC_NEED_PORT1;
        }
    }
    {
        dpls_calib_t reserve = {nv.vcap_gain_milli, nv.vcap_offset_mv};
        if (reserve_calib_valid(&reserve)) {
            channels[3].calibration = reserve;
            calibrated_mask |= DPLS_ADC_NEED_VCAP;
        }
    }
}

static void load_calibration(void)
{
    set_default_calibration();
    if (!load_calibration_v2())
        load_legacy_calibration();
}

static void finish_inflight_as_failed(void)
{
    adc_busy = false;
    adc_raw_ready = false;
    adc_event_failed = true;
    osal_set_event(task_id, process_event);
}

static void adc_evt(adc_Evt_t *event)
{
    uint8_t i;
    uint8_t n;

    if (inflight_index == DPLS_ADC_INDEX_NONE) {
        adc_busy = false;
        return;
    }
    if (event == NULL || event->type != HAL_ADC_EVT_DATA || event->data == NULL || event->size == 0u) {
        finish_inflight_as_failed();
        return;
    }

    n = event->size > MAX_ADC_SAMPLE_SIZE ? MAX_ADC_SAMPLE_SIZE : event->size;
    for (i = 0; i < n; ++i)
        adc_raw[i] = event->data[i];
    adc_raw_size = n;
    adc_raw_channel = event->ch;
    adc_raw_ready = true;
    adc_event_failed = false;
    adc_busy = false;
    osal_set_event(task_id, process_event);
}

static void adc_kick(void)
{
    adc_Cfg_t cfg;
    uint8_t i;

    if (!initialized || adc_busy || adc_raw_ready || inflight_index != DPLS_ADC_INDEX_NONE ||
        adc_pending == 0u)
        return;

    for (i = 0; i < DPLS_ADC_CHANNEL_COUNT; ++i) {
        if (adc_pending & channels[i].pending_bit)
            break;
    }
    if (i == DPLS_ADC_CHANNEL_COUNT)
        return;

    memset(&cfg, 0, sizeof(cfg));
    cfg.channel = channels[i].config_channel;
    cfg.is_continue_mode = FALSE;
    cfg.is_differential_mode = 0u;
    cfg.is_high_resolution = 0u;

    inflight_index = i;
    inflight_started_ms = (uint32_t)osal_GetSystemClock();
    adc_busy = true;
    if (hal_adc_config_channel(cfg, adc_evt) != PPlus_SUCCESS) {
        finish_inflight_as_failed();
        return;
    }
    if (hal_adc_start(INTERRUPT_MODE) != PPlus_SUCCESS) {
        (void)hal_adc_stop();
        finish_inflight_as_failed();
    }
}

bool dpls_phy6252_adc_init(uint8_t new_task_id, uint16_t new_process_event)
{
    uint8_t i;

    task_id = new_task_id;
    process_event = new_process_event;
    initialized = false;
    radio_gated = false;
    scan_mask = DPLS_ADC_NEED_SAFETY;
    memset(channels, 0, sizeof(channels));

    channels[0].pending_bit = DPLS_ADC_NEED_PORT1;
    channels[0].config_channel = ADC_BIT(ADC_CH3P_P20);
    channels[0].result_channel = ADC_CH9;
    channels[0].validity_bit = DPLS_STATE_PORT_1_VALID;

    channels[1].pending_bit = DPLS_ADC_NEED_PORT2;
    channels[1].config_channel = ADC_BIT(ADC_CH3N_P15);
    channels[1].result_channel = ADC_CH4;
    channels[1].validity_bit = DPLS_STATE_PORT_2_VALID;

    channels[2].pending_bit = DPLS_ADC_NEED_PORT_T;
    channels[2].config_channel = ADC_BIT(ADC_CH2N_P24);
    channels[2].result_channel = ADC_CH2;
    channels[2].validity_bit = DPLS_STATE_PORT_T_VALID;

    channels[3].pending_bit = DPLS_ADC_NEED_VCAP;
    channels[3].config_channel = ADC_BIT(ADC_CH1P_P23);
    channels[3].result_channel = ADC_CH1;
    channels[3].validity_bit = DPLS_STATE_RESERVE_VOLTAGE_VALID;

    for (i = 0; i < DPLS_ADC_CHANNEL_COUNT; ++i) {
        channels[i].cached_mv = 0u;
        channels[i].last_sample_ms = 0u;
    }

    load_calibration();
    adc_busy = false;
    adc_pending = 0u;
    inflight_index = DPLS_ADC_INDEX_NONE;
    inflight_started_ms = 0u;
    adc_raw_ready = false;
    adc_event_failed = false;
    next_cycle_ms = 0u;

    hal_adc_init();
    if (hal_pwrmgr_lock(MOD_ADCC) != PPlus_SUCCESS)
        return false;
    (void)hal_pwrmgr_unlock(MOD_ADCC);

    load_hw_adc_calibration();
    initialized = true;
    return true;
}

void dpls_phy6252_adc_set_radio_gated(bool enabled)
{
    radio_gated = enabled;
    if (!enabled)
        adc_kick();
}

void dpls_phy6252_adc_after_radio_event(void)
{
    if (initialized && radio_gated)
        adc_kick();
}

void dpls_phy6252_adc_set_full_scan(bool enabled)
{
    scan_mask = enabled ? DPLS_ADC_NEED_ALL : DPLS_ADC_NEED_SAFETY;
    adc_pending &= scan_mask;
    if (!enabled) {
        /* +2/+T are presentation-only while disconnected. Drop validity
         * immediately rather than showing a pre-disconnect value as live. */
        channels[1].last_sample_ms = 0u;
        channels[2].last_sample_ms = 0u;
    }
}

void dpls_phy6252_adc_tick(uint32_t now_ms)
{
    if (!initialized)
        return;

    if (inflight_index != DPLS_ADC_INDEX_NONE && adc_busy &&
        (uint32_t)(now_ms - inflight_started_ms) >= DPLS_ADC_CONVERSION_TIMEOUT_MS) {
        (void)hal_adc_stop();
        finish_inflight_as_failed();
        return;
    }

    if (adc_pending == 0u && !adc_busy && !adc_raw_ready &&
        inflight_index == DPLS_ADC_INDEX_NONE && elapsed(now_ms, next_cycle_ms)) {
        adc_pending = scan_mask;
        next_cycle_ms = now_ms + DPLS_ADC_PERIOD_MS;
        if (!radio_gated)
            adc_kick();
    }
}

void dpls_phy6252_adc_process(uint32_t now_ms)
{
    if (!initialized)
        return;

    if (inflight_index != DPLS_ADC_INDEX_NONE) {
        dpls_adc_channel_t *channel = &channels[inflight_index];
        if ((channel->pending_bit & scan_mask) != 0u &&
            !adc_event_failed && adc_raw_ready && adc_raw_size != 0u &&
            adc_raw_channel == channel->result_channel) {
            uint16_t pin_mv = dpls_adc_single_ended_mv(
                (uint8_t)channel->result_channel,
                (const uint16_t *)adc_raw,
                adc_raw_size,
                adc_hw_calibration_negative,
                adc_hw_calibration_positive,
                adc_Lambda[channel->result_channel]);
            /* The conversion helper already averages the complete vendor sample
             * packet. A second multi-second moving average delayed safety state
             * and retained an unnecessary ring buffer. */
            channel->cached_mv = dpls_calib_apply(&channel->calibration, pin_mv);
            channel->last_sample_ms = now_ms;
        }

        adc_pending = (uint8_t)(adc_pending & (uint8_t)~channel->pending_bit);
        adc_raw_ready = false;
        adc_event_failed = false;
        inflight_index = DPLS_ADC_INDEX_NONE;
    }

    /* During a BLE connection, perform at most one conversion in each quiet
     * post-radio window. Starting the next channel here would let an unlucky
     * task delay overlap the following connection event. */
    if (!radio_gated)
        adc_kick();
}

static bool channel_fresh(const dpls_adc_channel_t *channel, uint32_t now_ms)
{
    return channel->last_sample_ms != 0u &&
           (uint32_t)(now_ms - channel->last_sample_ms) <= DPLS_ADC_STALE_MS;
}

uint8_t dpls_phy6252_adc_validity(uint32_t now_ms)
{
    uint8_t flags = 0u;
    uint8_t i;
    for (i = 0; i < DPLS_ADC_CHANNEL_COUNT; ++i) {
        if (channel_fresh(&channels[i], now_ms))
            flags |= channels[i].validity_bit;
    }
    return flags;
}

bool dpls_phy6252_adc_fully_calibrated(void)
{
    return calibrated_mask == DPLS_ADC_NEED_ALL;
}

uint16_t dpls_phy6252_adc_port1_mv(void) { return channels[0].cached_mv; }
uint16_t dpls_phy6252_adc_port2_mv(void) { return channels[1].cached_mv; }
uint16_t dpls_phy6252_adc_port_t_mv(void) { return channels[2].cached_mv; }
uint16_t dpls_phy6252_adc_reserve_mv(void) { return channels[3].cached_mv; }
