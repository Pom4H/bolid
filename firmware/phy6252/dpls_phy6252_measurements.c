#include "dpls_phy6252_measurements.h"

#include "dpls_board.h"
#include "dpls_calib.h"
#include "dpls_phy6252_events.h"
#include "dpls_phy6252_storage.h"
#include "OSAL.h"
#if defined(__GNUC__)
#pragma GCC diagnostic ignored "-Wunused-function"
#endif
#include "adc.h"
#include "error.h"
#include <string.h>

#define DPLS_ADC_DECIMATE 1u
#define DPLS_ADC_WINDOW 8u
#define DPLS_ADC_NEED_PORT1 0x01u
#define DPLS_ADC_NEED_PORT2 0x02u
#define DPLS_ADC_NEED_PORT_T 0x04u
#define DPLS_ADC_NEED_VCAP 0x08u
#define DPLS_ADC_NEED_ALL (DPLS_ADC_NEED_PORT1 | DPLS_ADC_NEED_PORT2 | \
                           DPLS_ADC_NEED_PORT_T | DPLS_ADC_NEED_VCAP)
#define DPLS_LINE_PRESENT_MV 4000u
#define DPLS_LINE_ABSENT_MV 3000u
#define DPLS_RESERVE_LOW_MV 3700u
#define DPLS_RESERVE_OK_MV 4000u
#define DPLS_AUTOISO_TRIP_MV 3000u
#define DPLS_AUTOISO_CLEAR_MV 4500u

static uint8 task_id;
static dpls_calib_t line_calib;
static dpls_calib_t vcap_calib;
static bool line_calib_from_nv;

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
static uint8_t adc_pending;
static uint8_t adc_decimate;
static dpls_power_t power_state = DPLS_POWER_LINE;
static bool reserve_low_state;
static bool auto_isolation_active;
static bool line_established;

static volatile uint16_t adc_raw[MAX_ADC_SAMPLE_SIZE];
static volatile uint8_t adc_raw_size;
static volatile adc_CH_t adc_raw_channel;
static volatile bool adc_raw_ready;

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

static void adc_evt(adc_Evt_t *event)
{
    uint8_t i, n;
    if (event->type != HAL_ADC_EVT_DATA) {
        adc_busy = false;
        return;
    }
    n = event->size > MAX_ADC_SAMPLE_SIZE ? MAX_ADC_SAMPLE_SIZE : event->size;
    for (i = 0u; i < n; ++i) adc_raw[i] = event->data[i];
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

static void update_power_state(dpls_mode_t mode)
{
    if (line_window_count != 0u) {
        uint16_t line = cached_line_mv;
        if (power_state == DPLS_POWER_LINE && line < DPLS_LINE_ABSENT_MV)
            power_state = DPLS_POWER_RESERVE;
        else if (power_state == DPLS_POWER_RESERVE && line > DPLS_LINE_PRESENT_MV)
            power_state = DPLS_POWER_LINE;

        if (line > DPLS_LINE_PRESENT_MV) line_established = true;
        if (line_established && mode == DPLS_MODE_NORMAL) {
            if (!auto_isolation_active && line < DPLS_AUTOISO_TRIP_MV)
                auto_isolation_active = true;
            else if (auto_isolation_active && line > DPLS_AUTOISO_CLEAR_MV)
                auto_isolation_active = false;
        }
    }
    if (vcap_window_count != 0u) {
        uint16_t vcap = cached_vcap_mv;
        if (!reserve_low_state && vcap < DPLS_RESERVE_LOW_MV) reserve_low_state = true;
        else if (reserve_low_state && vcap > DPLS_RESERVE_OK_MV) reserve_low_state = false;
    }
}

void dpls_phy6252_measurements_init(uint8 new_task_id)
{
    task_id = new_task_id;
    line_window_count = line_window_pos = 0u;
    port2_window_count = port2_window_pos = 0u;
    port_t_window_count = port_t_window_pos = 0u;
    vcap_window_count = vcap_window_pos = adc_decimate = 0u;
    cached_line_mv = cached_port2_mv = cached_port_t_mv = cached_vcap_mv = 0u;
    adc_pending = 0u;
    adc_raw_ready = false;
    adc_busy = false;
    power_state = DPLS_POWER_LINE;
    reserve_low_state = false;
    auto_isolation_active = false;
    line_established = false;
    dpls_phy6252_storage_load_calibration(&line_calib, &vcap_calib, &line_calib_from_nv);
    hal_adc_init();
}

void dpls_phy6252_measurements_tick(bool connected, dpls_mode_t mode)
{
    if (++adc_decimate >= DPLS_ADC_DECIMATE) {
        adc_decimate = 0u;
        adc_pending = connected ? (uint8_t)DPLS_ADC_NEED_ALL
                                : (uint8_t)(DPLS_ADC_NEED_PORT1 | DPLS_ADC_NEED_VCAP);
        adc_kick();
    }
    update_power_state(mode);
}

void dpls_phy6252_measurements_process(void)
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
    adc_kick();
}

uint16_t dpls_phy6252_measurements_voltage_mv(void *context)
{
    (void)context;
    return cached_line_mv;
}

uint16_t dpls_phy6252_measurements_port1_mv(void *context)
{
    (void)context;
    return cached_line_mv;
}

uint16_t dpls_phy6252_measurements_port2_mv(void *context)
{
    (void)context;
    return cached_port2_mv;
}

uint16_t dpls_phy6252_measurements_port_t_mv(void *context)
{
    (void)context;
    return cached_port_t_mv;
}

uint16_t dpls_phy6252_measurements_reserve_mv(void *context)
{
    (void)context;
    return cached_vcap_mv;
}

dpls_power_t dpls_phy6252_measurements_power_source(void *context)
{
    (void)context;
    return power_state;
}

bool dpls_phy6252_measurements_reserve_low(void *context)
{
    (void)context;
    return reserve_low_state;
}

bool dpls_phy6252_measurements_real_short(void *context)
{
    (void)context;
    return auto_isolation_active;
}

uint8_t dpls_phy6252_measurements_validity(void *context)
{
    uint8_t flags = 0u;
    (void)context;
    if (line_window_count != 0u)
        flags |= DPLS_STATE_PORT_1_VALID | DPLS_STATE_POWER_VALID | DPLS_STATE_AUTOISO_VALID;
    if (port2_window_count != 0u) flags |= DPLS_STATE_PORT_2_VALID;
    if (port_t_window_count != 0u) flags |= DPLS_STATE_PORT_T_VALID;
    if (vcap_window_count != 0u) flags |= DPLS_STATE_RESERVE_VOLTAGE_VALID;
    if (line_calib_from_nv) flags |= DPLS_STATE_ADC_CALIBRATED;
    return flags;
}

bool dpls_phy6252_measurements_line_calibrated(void)
{
    return line_calib_from_nv;
}
