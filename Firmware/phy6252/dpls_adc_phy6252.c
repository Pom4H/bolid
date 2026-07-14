/*
 * Test-DPLS ADC driver for PHY6252.
 *
 * Replaces the PHY62XX SDK 3.1.1 adc.c only in the Test-DPLS target. The old
 * one-shot ISR calls hal_adc_stop(), which waits forever for intr_status == 0
 * while ADC_USE_TIMEOUT is disabled. A stuck/reasserted status bit therefore
 * keeps the Cortex-M0 in ADCC_IRQn until the 2 s watchdog resets the device.
 *
 * This implementation backports the relevant SDK 3.1.2 behaviour: bounded
 * shutdown, ADCC module reset, pending-IRQ clear and correct extraction of two
 * 12-bit samples from each 32-bit ADC data register. The API remains compatible
 * with the SDK 3.1.1 adc.h already used by the application.
 */

#include "adc.h"

#include "clock.h"
#include "error.h"
#include "jump_function.h"
#include "pwrmgr.h"
#include "version.h"

#include <string.h>

#define ADC_RAW_SAMPLE_COUNT ((MAX_ADC_SAMPLE_SIZE - 2u) * 2u)
#define ADC_IRQ_STATUS_MASK   0x000003ffu
#define ADC_SINGLE_CHANNELS   0xfcu /* logical ADC channels 2..7 */

typedef struct {
    bool initialized;
    bool enabled;
    bool continue_mode;
    uint8_t requested;
    uint8_t expected_status;
    uint8_t completed_status;
    adc_Hdl_t handlers[ADC_CH_NUM];
} adc_state_t;

static adc_state_t s_adc;
static uint8_t s_calibration_loaded;
static uint16_t s_calibration_positive = 0x0fffu;
static uint16_t s_calibration_negative = 0x0fffu;

gpio_pin_e s_pinmap[ADC_CH_NUM] = {
    GPIO_DUMMY, GPIO_DUMMY, P11, P23, P24, P14, P15, P20, GPIO_DUMMY,
};

static void adc_wakeup_handler(void)
{
    NVIC_SetPriority((IRQn_Type)ADCC_IRQn, IRQ_PRIO_HAL);
}

static void clear_state_keep_init(void)
{
    bool initialized = s_adc.initialized;
    memset(&s_adc, 0, sizeof(s_adc));
    s_adc.initialized = initialized;
}

/* Hardware reports the opposite bit of each P/N channel pair. */
static uint8_t expected_status_for(uint8_t requested)
{
    uint8_t result = 0u;
    uint8_t ch;

    for (ch = 2u; ch <= 7u; ++ch) {
        if (requested & BIT(ch))
            result |= (uint8_t)BIT((ch & 1u) ? (ch - 1u) : (ch + 1u));
    }
    return result;
}

static adc_CH_t logical_channel(uint8_t status_ch)
{
    return (adc_CH_t)((status_ch & 1u) ? (status_ch - 1u) : (status_ch + 1u));
}

static uint8_t analog_index(adc_CH_t ch)
{
    switch (ch) {
    case ADC_CH1N_P11: return 0u;
    case ADC_CH1P_P23: return 1u;
    case ADC_CH2N_P24: return 2u;
    case ADC_CH2P_P14: return 3u;
    case ADC_CH3N_P15: return 4u;
    case ADC_CH3P_P20: return 7u;
    default: return 0xffu;
    }
}

static void set_resolution(adc_CH_t ch, bool high)
{
    uint8_t aio = analog_index(ch);
    if (aio == 0xffu) return;

    /* PMCTL2_1 low bit = high-resolution path, upper bit = normal path. */
    subWriteReg(&(AP_AON->PMCTL2_1), aio + 8u, aio + 8u, high ? 0u : 1u);
    subWriteReg(&(AP_AON->PMCTL2_1), aio, aio, high ? 1u : 0u);
}

static void clear_channel_registers(void)
{
    AP_PCRM->ADC_CTL0 &= ~(BIT(20) | BIT(4));
    AP_PCRM->ADC_CTL1 &= ~(BIT(20) | BIT(4));
    AP_PCRM->ADC_CTL2 &= ~(BIT(20) | BIT(4));
    AP_PCRM->ADC_CTL3 &= ~(BIT(20) | BIT(4));
}

static void enable_channel_register(adc_CH_t ch)
{
    switch (ch) {
    case ADC_CH1N_P11: AP_PCRM->ADC_CTL1 |= BIT(20); break;
    case ADC_CH1P_P23: AP_PCRM->ADC_CTL1 |= BIT(4); break;
    case ADC_CH2N_P24: AP_PCRM->ADC_CTL2 |= BIT(20); break;
    case ADC_CH2P_P14: AP_PCRM->ADC_CTL2 |= BIT(4); break;
    case ADC_CH3N_P15: AP_PCRM->ADC_CTL3 |= BIT(20); break;
    case ADC_CH3P_P20: AP_PCRM->ADC_CTL3 |= BIT(4); break;
    default: break;
    }
}

static void disable_analog_pin(adc_CH_t ch)
{
    gpio_pin_e pin;

    if ((uint8_t)ch >= ADC_CH_NUM) return;
    pin = s_pinmap[(uint8_t)ch];
    if (pin == GPIO_DUMMY) return;

    hal_gpio_cfg_analog_io(pin, Bit_DISABLE);
    hal_gpio_pin_init(pin, GPIO_INPUT);
    hal_gpio_pull_set(pin, GPIO_FLOATING);
}

static void stop_conversion(void)
{
    uint8_t ch;
    uint8_t requested = s_adc.requested;

    /* No wait loop here. In SDK 3.1.1 that loop has no active timeout and is the
     * leading explanation for the observed watchdog resets. */
    MASK_ADC_INT;
    NVIC_DisableIRQ((IRQn_Type)ADCC_IRQn);
    JUMP_FUNCTION(ADCC_IRQ_HANDLER) = 0u;
    AP_ADCC->intr_clear = 0x1ffu;

    AP_PCRM->ANA_CTL &= ~BIT(3); /* ADC off */
    for (ch = 2u; ch <= 7u; ++ch) {
        if (requested & BIT(ch)) disable_analog_pin((adc_CH_t)ch);
    }
    AP_PCRM->ANA_CTL &= ~BIT(0); /* analog LDO off */

    /* SDK 3.1.2 resets ADCC and clears pending IRQ. It intentionally no longer
     * clears CLKHF_CTL1 bit 13 on every sample, avoiding repeated shared-clock
     * teardown while the BLE radio is active. */
    hal_clk_reset(MOD_ADCC);
    hal_clk_gate_disable(MOD_ADCC);

    clear_state_keep_init();
    (void)hal_pwrmgr_unlock(MOD_ADCC);
    NVIC_ClearPendingIRQ((IRQn_Type)ADCC_IRQn);
}

void __attribute__((used)) hal_ADC_IRQHandler(void)
{
    static uint16_t samples[ADC_RAW_SAMPLE_COUNT];
    uint32_t raw_status = AP_ADCC->intr_status & ADC_IRQ_STATUS_MASK;
    uint32_t pending = raw_status & s_adc.expected_status;
    uint32_t unexpected = raw_status & ~(uint32_t)s_adc.expected_status;
    uint8_t status_ch;

    MASK_ADC_INT;
    if (unexpected) AP_ADCC->intr_clear = unexpected;

    for (status_ch = 2u; status_ch <= 7u; ++status_ch) {
        uint8_t i;
        adc_CH_t logical;

        if ((pending & BIT(status_ch)) == 0u) continue;

        /* Each ADC register contains two packed 12-bit samples. */
        for (i = 0u; i < (MAX_ADC_SAMPLE_SIZE - 2u); ++i) {
            uint32_t packed = read_reg(ADC_CH_BASE +
                                       (uint32_t)status_ch * 0x80u +
                                       (uint32_t)(i + 2u) * 4u);
            samples[(uint16_t)i * 2u] = (uint16_t)(packed & 0x0fffu);
            samples[(uint16_t)i * 2u + 1u] = (uint16_t)((packed >> 16) & 0x0fffu);
        }

        AP_ADCC->intr_clear = BIT(status_ch);
        s_adc.completed_status |= (uint8_t)BIT(status_ch);
        logical = logical_channel(status_ch);

        if (s_adc.enabled && (uint8_t)logical < ADC_CH_NUM &&
            s_adc.handlers[(uint8_t)logical]) {
            adc_Evt_t event;
            event.type = HAL_ADC_EVT_DATA;
            event.ch = logical;
            event.data = samples;
            event.size = (uint8_t)ADC_RAW_SAMPLE_COUNT;
            s_adc.handlers[(uint8_t)logical](&event);
        }
    }

    if (s_adc.expected_status != 0u &&
        (s_adc.completed_status & s_adc.expected_status) == s_adc.expected_status) {
        if (!s_adc.continue_mode) {
            stop_conversion();
            return;
        }
        s_adc.completed_status = 0u;
    }

    ENABLE_ADC_INT;
}

void hal_adc_init(void)
{
    int rc;

    if (s_adc.initialized) return;
    memset(&s_adc, 0, sizeof(s_adc));
    rc = hal_pwrmgr_register(MOD_ADCC, NULL, adc_wakeup_handler);
    if (rc == PPlus_SUCCESS || rc == PPlus_ERR_INVALID_STATE)
        s_adc.initialized = true;
}

int hal_adc_clock_config(adc_CLOCK_SEL_t clock)
{
    if (!s_adc.initialized) return PPlus_ERR_NOT_REGISTED;
    subWriteReg(0x4000f07cu, 2, 1, clock);
    return PPlus_SUCCESS;
}

int hal_adc_config_channel(adc_Cfg_t cfg, adc_Hdl_t handler)
{
    uint8_t ch;

    if (!s_adc.initialized) return PPlus_ERR_NOT_REGISTED;
    if (s_adc.enabled) return PPlus_ERR_BUSY;
    if (!handler) return PPlus_ERR_INVALID_PARAM;
    if ((cfg.channel & ADC_SINGLE_CHANNELS) == 0u ||
        (cfg.channel & (uint8_t)~ADC_SINGLE_CHANNELS) != 0u ||
        cfg.is_differential_mode != 0u)
        return PPlus_ERR_NOT_SUPPORTED;

    clear_state_keep_init();
    s_adc.continue_mode = cfg.is_continue_mode;
    s_adc.requested = cfg.channel;
    s_adc.expected_status = expected_status_for(cfg.channel);

    if ((AP_PCR->SW_CLK & BIT(MOD_ADCC)) == 0u) hal_clk_gate_enable(MOD_ADCC);

    /* Same PHY6252 clock setup as the vendor driver. */
    AP_PCRM->CLKSEL |= BIT(6);
    AP_PCRM->CLKHF_CTL0 |= BIT(18);
    AP_PCRM->CLKHF_CTL1 |= BIT(7);
    AP_PCRM->CLKHF_CTL1 &= ~BIT(21);
    AP_PCRM->CLKHF_CTL1 |= BIT(13);
    AP_PCRM->ADC_CTL4 |= BIT(4);
    AP_PCRM->ADC_CTL4 |= BIT(0);

    AP_AON->PMCTL2_1 = 0u;
    clear_channel_registers();
    AP_PCRM->ANA_CTL &= ~BIT(23); /* micbias off */
    AP_PCRM->ADC_CTL4 &= ~BIT(4); /* auto sampling mode */

    for (ch = 2u; ch <= 7u; ++ch) {
        if (cfg.channel & BIT(ch)) {
            gpio_pin_e pin = s_pinmap[ch];
            set_resolution((adc_CH_t)ch, (cfg.is_high_resolution & BIT(ch)) != 0u);
            hal_gpio_pull_set(pin, GPIO_FLOATING);
            hal_gpio_ds_control(pin, Bit_ENABLE);
            hal_gpio_cfg_analog_io(pin, Bit_ENABLE);
            enable_channel_register((adc_CH_t)ch);
            s_adc.handlers[ch] = handler;
        }
    }

    return PPlus_SUCCESS;
}

int hal_adc_start(void)
{
    int rc;

    if (!s_adc.initialized) return PPlus_ERR_NOT_REGISTED;
    if (s_adc.requested == 0u || s_adc.expected_status == 0u)
        return PPlus_ERR_INVALID_STATE;

    rc = hal_pwrmgr_lock(MOD_ADCC);
    if (rc != PPlus_SUCCESS) {
        stop_conversion(); /* also releases pins/gate configured above */
        return rc;
    }

    s_adc.enabled = true;
    s_adc.completed_status = 0u;
    JUMP_FUNCTION(ADCC_IRQ_HANDLER) = (uint32_t)&hal_ADC_IRQHandler;
    AP_PCRM->ANA_CTL |= BIT(3);
    AP_PCRM->ANA_CTL |= BIT(0);
    AP_ADCC->intr_clear = 0x1ffu;
    NVIC_ClearPendingIRQ((IRQn_Type)ADCC_IRQn);
    NVIC_EnableIRQ((IRQn_Type)ADCC_IRQn);
    AP_ADCC->intr_mask = 0x1ffu;
    return PPlus_SUCCESS;
}

int hal_adc_stop(void)
{
    if (!s_adc.initialized) return PPlus_ERR_NOT_REGISTED;
    if (s_adc.requested == 0u) return PPlus_SUCCESS;
    stop_conversion();
    return PPlus_SUCCESS;
}

static void load_calibration(void)
{
    uint32_t word;

    if (s_calibration_loaded) return;
    s_calibration_loaded = 1u;
    word = read_reg(0x11001000u);
    s_calibration_negative = (uint16_t)(word & 0x0fffu);
    s_calibration_positive = (uint16_t)((word >> 16) & 0x0fffu);

    if (s_calibration_negative < 0x733u || s_calibration_negative > 0x8ccu ||
        s_calibration_positive < 0x733u || s_calibration_positive > 0x8ccu) {
        s_calibration_negative = 0x0fffu;
        s_calibration_positive = 0x0fffu;
    }
}

#if (SDK_VER_CHIP == __DEF_CHIP_QFN32__)
static const uint32_t adc_lambda[ADC_CH_NUM] = {
    0u, 0u, 4519602u, 4308639u, 4263287u,
    4482718u, 4180401u, 4072069u, 0u,
};
#else
static const uint32_t adc_lambda[ADC_CH_NUM] = {
    0u, 0u, 4488156u, 4308639u, 4263287u,
    4467981u, 4142931u, 4054721u, 0u,
};
#endif

float hal_adc_value_cal(adc_CH_t channel,
                        uint16_t *buffer,
                        uint32_t size,
                        uint8_t high_resolution,
                        uint8_t differential_mode)
{
    uint32_t i;
    int32_t sum = 0;
    float result;

    if (!buffer || size == 0u || (uint8_t)channel >= ADC_CH_NUM) return 0.0f;
    for (i = 0u; i < size; ++i) sum += (int32_t)(buffer[i] & 0x0fffu);

    load_calibration();
    result = (float)sum / (float)size;

    if (s_calibration_positive != 0x0fffu && s_calibration_negative != 0x0fffu) {
        float delta = ((float)s_calibration_positive - (float)s_calibration_negative) / 2.0f;
        float denominator = (float)s_calibration_positive + (float)s_calibration_negative;
        if (((uint8_t)channel & 1u) != 0u)
            result = differential_mode
                ? ((result - 2048.0f - delta) * 2.0f / denominator)
                : ((result - delta) / denominator);
        else
            result = differential_mode
                ? ((result - 2048.0f - delta) * 2.0f / denominator)
                : ((result + delta) / denominator);
    } else {
        result = differential_mode ? (result / 2048.0f - 1.0f)
                                   : (result / 4096.0f);
    }

    if (high_resolution) result *= 0.8f;
    else result = result * (float)adc_lambda[(uint8_t)channel] * 0.8f / 1000000.0f;
    return result;
}
