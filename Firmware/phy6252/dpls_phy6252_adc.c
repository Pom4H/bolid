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

/* One complete four-channel scan starts roughly once per second. The same 1 Hz
 * housekeeping tick also owns lost-IRQ recovery so a watchdog costs no extra
 * wakeups. A normal conversion is orders of magnitude faster; one full tick is
 * therefore a conservative wedge timeout, not the expected conversion time. */
#define DPLS_ADC_PERIOD_MS 1000u
#define DPLS_ADC_CONVERSION_TIMEOUT_MS 1000u
#define DPLS_ADC_WINDOW 8u
#define DPLS_ADC_STALE_MS 3500u

#define DPLS_ADC_NEED_PORT1 0x01u
#define DPLS_ADC_NEED_PORT2 0x02u
#define DPLS_ADC_NEED_PORT_T 0x04u
#define DPLS_ADC_NEED_VCAP 0x08u
#define DPLS_ADC_NEED_ALL (DPLS_ADC_NEED_PORT1 | DPLS_ADC_NEED_PORT2 | \
                           DPLS_ADC_NEED_PORT_T | DPLS_ADC_NEED_VCAP)
#define DPLS_ADC_INDEX_NONE 0xffu
#define DPLS_ADC_CHANNEL_COUNT 4u

/* Reserve divider is independent from the 1/31 DPLS line dividers. 2.000 is
 * still provisional until the power board is measured. */
#define DPLS_VCAP_NOMINAL_GAIN_MILLI 2000u
#define DPLS_VCAP_GAIN_MIN_MILLI 1000u
#define DPLS_VCAP_GAIN_MAX_MILLI 5000u
#define DPLS_VCAP_OFFSET_LIMIT_MV 2000

/* adc_Lambda is a public const table in the pinned vendor adc.c. Referencing the
 * table avoids duplicating package-specific QFN32 coefficients in product code. */
extern const unsigned int adc_Lambda[ADC_CH_NUM];

/* Legacy SNV 0x83 layout used by 1.1.0..1.1.3-rc.1. */
typedef struct {
    uint32_t magic;
    uint32_t line_gain_milli;
    int32_t line_offset_mv;
    uint32_t vcap_gain_milli;
    int32_t vcap_offset_mv;
    uint16_t crc;
} dpls_calib_nv_v1_t;

/* V2 is encoded explicitly, not through a C struct, so the persistent format is
 * independent of compiler padding/ABI:
 *   0..3   magic
 *   4      version (=2)
 *   5..7   reserved zero
 *   8..39  four {gain:u32, offset:i32}: +1,+2,+T,reserve
 *   40..41 CRC16 over bytes 0..39
 */
#define DPLS_CALIB_V2_DATA_SIZE 40u
#define DPLS_CALIB_V2_SIZE 42u

/* Compile-time electrical contract: changing board.h without changing the ADC
 * mux mapping must fail the target build rather than sample another net. */
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
    uint16_t window[DPLS_ADC_WINDOW];
    uint8_t window_count;
    uint8_t window_pos;
    volatile uint16_t cached_mv;
    uint32_t last_sample_ms;
} dpls_adc_channel_t;

static dpls_adc_channel_t channels[DPLS_ADC_CHANNEL_COUNT];
static uint8_t calibrated_mask;
static uint8_t task_id;
static uint16_t process_event;
static bool initialized;
static volatile bool adc_busy;
static uint8_t adc_pending;
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

    /* Legacy line calibration was measured on P20 only. Do not copy its
     * component-specific correction to the independent +2/+T dividers. */
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

static uint16_t fold_window(dpls_adc_channel_t *channel, uint16_t value)
{
    uint32_t sum = 0u;
    uint8_t i;
    channel->window[channel->window_pos] = value;
    channel->window_pos = (uint8_t)((channel->window_pos + 1u) % DPLS_ADC_WINDOW);
    if (channel->window_count < DPLS_ADC_WINDOW)
        ++channel->window_count;
    for (i = 0u; i < channel->window_count; ++i)
        sum += channel->window[i];
    return (uint16_t)(sum / channel->window_count);
}

static void finish_inflight_as_failed(void)
{
    adc_busy = false;
    adc_raw_ready = false;
    adc_event_failed = true;
    osal_set_event(task_id, process_event);
}

/* ISR is deliberately minimal: copy raw words, record completion and wake the
 * OSAL task. No conversion/calibration/averaging/flash/BLE runs here. */
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
        channels[i].window_count = 0u;
        channels[i].window_pos = 0u;
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

    /* Vendor hal_adc_init() is void and ignores the return value from
     * hal_pwrmgr_register(MOD_ADCC). Probe the registration once at boot; a
     * missing MOD_ADCC slot would otherwise make hal_adc_start() run while
     * sleep is still allowed. This lock/unlock pair is init-only and adds no
     * recurring wake or steady-state current. */
    hal_adc_init();
    if (hal_pwrmgr_lock(MOD_ADCC) != PPlus_SUCCESS)
        return false;
    (void)hal_pwrmgr_unlock(MOD_ADCC);

    load_hw_adc_calibration();
    initialized = true;
    return true;
}

void dpls_phy6252_adc_tick(uint32_t now_ms)
{
    if (!initialized)
        return;

    /* A lost ADC IRQ must not freeze all four measurements forever. Recovery is
     * checked by the existing 1 Hz housekeeping tick, so no watchdog-only timer
     * wakes the MCU. */
    if (inflight_index != DPLS_ADC_INDEX_NONE && adc_busy &&
        (uint32_t)(now_ms - inflight_started_ms) >= DPLS_ADC_CONVERSION_TIMEOUT_MS) {
        (void)hal_adc_stop();
        finish_inflight_as_failed();
        return;
    }

    /* Never overwrite a cycle still in flight. */
    if (adc_pending == 0u && !adc_busy && !adc_raw_ready &&
        inflight_index == DPLS_ADC_INDEX_NONE && elapsed(now_ms, next_cycle_ms)) {
        adc_pending = DPLS_ADC_NEED_ALL;
        next_cycle_ms = now_ms + DPLS_ADC_PERIOD_MS;
        adc_kick();
    }
}

void dpls_phy6252_adc_process(uint32_t now_ms)
{
    if (!initialized)
        return;

    if (inflight_index != DPLS_ADC_INDEX_NONE) {
        dpls_adc_channel_t *channel = &channels[inflight_index];
        if (!adc_event_failed && adc_raw_ready && adc_raw_size != 0u &&
            adc_raw_channel == channel->result_channel) {
            uint16_t pin_mv = dpls_adc_single_ended_mv(
                (uint8_t)channel->result_channel,
                (const uint16_t *)adc_raw,
                adc_raw_size,
                adc_hw_calibration_negative,
                adc_hw_calibration_positive,
                adc_Lambda[channel->result_channel]);
            uint16_t measured = dpls_calib_apply(&channel->calibration, pin_mv);
            channel->cached_mv = fold_window(channel, measured);
            channel->last_sample_ms = now_ms;
        }

        /* Success, explicit failure and timeout all consume exactly one channel.
         * It is retried next cycle; one fault can never starve the other three. */
        adc_pending = (uint8_t)(adc_pending & (uint8_t)~channel->pending_bit);
        adc_raw_ready = false;
        adc_event_failed = false;
        inflight_index = DPLS_ADC_INDEX_NONE;
    }

    adc_kick();
}

static bool channel_fresh(const dpls_adc_channel_t *channel, uint32_t now_ms)
{
    return channel->window_count != 0u &&
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
