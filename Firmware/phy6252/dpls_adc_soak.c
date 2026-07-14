/*
 * ADC/BLE coexistence soak task.
 *
 * This task keeps the normal Test-DPLS BLE application running and triggers a
 * one-shot ADC conversion once per second. It is intentionally separate from
 * dpls_phy6252_app.c so the diagnostic branch can prove the driver and clock/
 * sleep interaction before measured values are allowed to affect product logic.
 */

#include "dpls_adc_soak.h"

#include "OSAL.h"
#include "adc.h"
#include "error.h"
#include "log.h"

#include <string.h>

#define ADC_SOAK_START_EVT    0x0001u
#define ADC_SOAK_DONE_EVT     0x0002u
#define ADC_SOAK_TIMEOUT_EVT  0x0004u

#define ADC_SOAK_INITIAL_DELAY_MS 3000u
#define ADC_SOAK_INTERVAL_MS      1000u
#define ADC_SOAK_TIMEOUT_MS        500u

/* Stage 1 is deliberately P20-only. Set to 1 after that survives the complete
 * matrix (advertising, connected idle, active GATT traffic) to add P23/VCAP. */
#ifndef DPLS_ADC_SOAK_INCLUDE_VCAP
#define DPLS_ADC_SOAK_INCLUDE_VCAP 0
#endif

static uint8 s_task_id;
static volatile uint8 s_ready_mask;
static volatile uint8 s_line_count;
static volatile uint8 s_vcap_count;
static uint16 s_line_samples[MAX_ADC_SAMPLE_SIZE];
static uint16 s_vcap_samples[MAX_ADC_SAMPLE_SIZE];
static uint32 s_started;
static uint32 s_completed;
static uint32 s_timeouts;
static uint32 s_start_errors;

static uint8 expected_ready_mask(void)
{
    uint8 mask = (uint8)BIT(ADC_CH9);
#if DPLS_ADC_SOAK_INCLUDE_VCAP
    mask |= (uint8)BIT(ADC_CH1);
#endif
    return mask;
}

static void adc_soak_callback(adc_Evt_t *event)
{
    uint8 i;
    uint8 count;

    if (!event || event->type != HAL_ADC_EVT_DATA || !event->data) return;
    count = event->size > MAX_ADC_SAMPLE_SIZE ? MAX_ADC_SAMPLE_SIZE : event->size;

    if (event->ch == ADC_CH9) {
        for (i = 0u; i < count; ++i) s_line_samples[i] = event->data[i];
        s_line_count = count;
        s_ready_mask |= (uint8)BIT(ADC_CH9);
#if DPLS_ADC_SOAK_INCLUDE_VCAP
    } else if (event->ch == ADC_CH1) {
        for (i = 0u; i < count; ++i) s_vcap_samples[i] = event->data[i];
        s_vcap_count = count;
        s_ready_mask |= (uint8)BIT(ADC_CH1);
#endif
    }

    if ((s_ready_mask & expected_ready_mask()) == expected_ready_mask()) {
        osal_set_event(s_task_id, ADC_SOAK_DONE_EVT);
    }
}

static void schedule_next(uint16 delay_ms)
{
    osal_start_timerEx(s_task_id, ADC_SOAK_START_EVT, delay_ms);
}

static void start_sample(void)
{
    adc_Cfg_t cfg;
    int rc;

    memset(&cfg, 0, sizeof(cfg));
    cfg.channel = ADC_BIT(ADC_CH3P_P20);
#if DPLS_ADC_SOAK_INCLUDE_VCAP
    cfg.channel |= ADC_BIT(ADC_CH1P_P23);
#endif
    cfg.is_continue_mode = FALSE;
    cfg.is_differential_mode = 0u;
    cfg.is_high_resolution = 0u;

    s_ready_mask = 0u;
    s_line_count = 0u;
    s_vcap_count = 0u;

    rc = hal_adc_config_channel(cfg, adc_soak_callback);
    if (rc != PPlus_SUCCESS) {
        ++s_start_errors;
        LOG("[ADC SOAK] config rc=%d starts=%lu done=%lu timeout=%lu err=%lu\n",
            rc, s_started, s_completed, s_timeouts, s_start_errors);
        schedule_next(ADC_SOAK_INTERVAL_MS);
        return;
    }

    rc = hal_adc_start();
    if (rc != PPlus_SUCCESS) {
        ++s_start_errors;
        (void)hal_adc_stop();
        LOG("[ADC SOAK] start rc=%d starts=%lu done=%lu timeout=%lu err=%lu\n",
            rc, s_started, s_completed, s_timeouts, s_start_errors);
        schedule_next(ADC_SOAK_INTERVAL_MS);
        return;
    }

    ++s_started;
    osal_start_timerEx(s_task_id, ADC_SOAK_TIMEOUT_EVT, ADC_SOAK_TIMEOUT_MS);
}

static void finish_sample(void)
{
    uint32 line_mv;
#if DPLS_ADC_SOAK_INCLUDE_VCAP
    uint32 vcap_mv;
#endif

    osal_stop_timerEx(s_task_id, ADC_SOAK_TIMEOUT_EVT);
    ++s_completed;

    line_mv = s_line_count
        ? (uint32)(hal_adc_value_cal(ADC_CH9, s_line_samples, s_line_count, FALSE, FALSE) * 1000.0f + 0.5f)
        : 0u;
#if DPLS_ADC_SOAK_INCLUDE_VCAP
    vcap_mv = s_vcap_count
        ? (uint32)(hal_adc_value_cal(ADC_CH1, s_vcap_samples, s_vcap_count, FALSE, FALSE) * 1000.0f + 0.5f)
        : 0u;
#endif

    if (s_completed == 1u || (s_completed % 10u) == 0u) {
#if DPLS_ADC_SOAK_INCLUDE_VCAP
        LOG("[ADC SOAK] ok n=%lu p20=%lu mV p23=%lu mV starts=%lu timeout=%lu err=%lu\n",
            s_completed, line_mv, vcap_mv, s_started, s_timeouts, s_start_errors);
#else
        LOG("[ADC SOAK] ok n=%lu p20=%lu mV starts=%lu timeout=%lu err=%lu\n",
            s_completed, line_mv, s_started, s_timeouts, s_start_errors);
#endif
    }

    schedule_next(ADC_SOAK_INTERVAL_MS);
}

static void sample_timeout(void)
{
    ++s_timeouts;
    (void)hal_adc_stop();
    LOG("[ADC SOAK] TIMEOUT starts=%lu done=%lu timeout=%lu err=%lu\n",
        s_started, s_completed, s_timeouts, s_start_errors);
    schedule_next(ADC_SOAK_INTERVAL_MS);
}

void DplsAdcSoak_Init(uint8 task_id)
{
    s_task_id = task_id;
    s_ready_mask = 0u;
    s_started = s_completed = s_timeouts = s_start_errors = 0u;
    hal_adc_init();
    schedule_next(ADC_SOAK_INITIAL_DELAY_MS);
}

uint16 DplsAdcSoak_ProcessEvent(uint8 task_id, uint16 events)
{
    (void)task_id;

    if (events & ADC_SOAK_START_EVT) {
        start_sample();
        return events ^ ADC_SOAK_START_EVT;
    }
    if (events & ADC_SOAK_DONE_EVT) {
        finish_sample();
        return events ^ ADC_SOAK_DONE_EVT;
    }
    if (events & ADC_SOAK_TIMEOUT_EVT) {
        sample_timeout();
        return events ^ ADC_SOAK_TIMEOUT_EVT;
    }
    return 0u;
}
