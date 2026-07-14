/*
 * Test-DPLS ADC driver for PHY6252.
 *
 * This is a deliberately small replacement for the PHY62XX SDK 3.1.1 adc.c.
 * The 3.1.1 one-shot IRQ calls hal_adc_stop(), whose interrupt-status clear loop
 * has no timeout (ADC_USE_TIMEOUT=0). If the status bit remains asserted, the
 * Cortex-M0 never leaves ADCC_IRQn, OSAL idle cannot feed the 2 s watchdog, and
 * the board resets. PHY62XX SDK 3.1.2 reworked this exact path: no unbounded
 * loop, module reset, pending-IRQ clear, and corrected sample extraction.
 *
 * The public API remains compatible with the 3.1.1 adc.h used by this project.
 * Only the single-ended channels used by the product are required, but the six
 * normal ADC pins are supported to keep the driver unsurprising.
 */

#include "adc.h"

#include "clock.h"
#include "error.h"
#include "jump_function.h"
#include "pwrmgr.h"
#include "version.h"

#include <string.h>

#define DPLS_ADC_RAW_SAMPLE_COUNT ((MAX_ADC_SAMPLE_SIZE - 2u) * 2u)
#define DPLS_ADC_STATUS_MASK      0x000003ffu
#define DPLS_ADC_CHANNEL_MASK     0xfcu /* logical channels 2..7 */

typedef struct {
    bool initialized;
    bool enabled;
    bool continue_mode;
    uint8_t requested_channels;
    uint8_t status_channels;
    uint8_t completed_status;
    adc_Hdl_t handlers[ADC_CH_NUM];
} dpls_adc_context_t;

static dpls_adc_context_t s_adc;
static uint8_t s_calibration_loaded;
static uint16_t s_calibration_positive = 0x0fffu;
static uint16_t s_calibration_negative = 0x0fffu;

/* Required by adc.h and useful for pin cleanup. */
gpio_pin_e s_pinmap[ADC_CH_NUM] = {
    GPIO_DUMMY,
    GPIO_DUMMY,
    P11,
    P23,
    P24,
    P14,
    P15,
    P20,
    GPIO_DUMMY,
};

static void adc_wakeup_handler(void)
{
    NVIC_SetPriority((IRQn_Type)ADCC_IRQn, IRQ_PRIO_HAL);
}

static void clear_runtime_context(void)
{
    bool initialized = s_adc.initialized;
    memset(&s_adc, 0, sizeof(s_adc));
    s_adc.initialized = initialized;
}

static uint8_t status_mask_for_channels(uint8_t channels)
{
    uint8_t status = 0u;
    uint8_t channel;

    for (channel = 2u; channel <= 7u; ++channel) {
        if (channels & BIT(channel)) {
            /* PHY6252 ADCC reports the opposite member of each P/N pair. */
            status |= (uint8_t)BIT((channel & 1u) ? (channel - 1u) : (channel + 1u));
        }
    }
    return status;
}

static adc_CH_t logical_channel_for_status(uint8_t status_channel)
{
    return (adc_CH_t)((status_channel & 1u) ? (status_channel - 1u)
                                            : (status_channel + 1u));
}

static void set_sampling_resolution(adc_CH_t channel,
                                    bool high_resolution,
                                    bool differential)
{
    uint8_t aio = 0u;
    uint8_t paired_aio = 0u;

    switch (channel) {
    case ADC_CH1N_P11: aio = 0u; paired_aio = 1u; break;
    case ADC_CH1P_P23: aio = 1u; paired_aio = 0u; break;
    case ADC_CH2N_P24: aio = 2u; paired_aio = 3u; break;
    case ADC_CH2P_P14: aio = 3u; paired_aio = 2u; break;
    case ADC_CH3N_P15: aio = 4u; paired_aio = 7u; break;
    case ADC_CH3P_P20: aio = 7u; paired_aio = 4u; break;
    default: return;
    }

    if (high_resolution) {
        if (differential) {
            subWriteReg(&(AP_AON->PMCTL2_1), paired_aio + 8u, paired_aio + 8u, 0u);
            subWriteReg(&(AP_AON->PMCTL2_1), paired_aio, paired_aio, 1u);
        }
        subWriteReg(&(AP_AON->PMCTL2_1), aio + 8u, aio + 8u, 0u);
        subWriteReg(&(AP_AON->PMCTL2_1), aio, aio, 1u);
    } else {
        if (differential) {
            subWriteReg(&(AP_AON->PMCTL2_1), paired_aio + 8u, paired_aio + 8u, 1u);
            subWriteReg(&(AP_AON->PMCTL2_1), paired_aio, paired_aio, 0u);
        }
        subWriteReg(&(AP_AON->PMCTL2_1), aio + 8u, aio + 8u, 1u);
        subWriteReg(&(AP_AON->PMCTL2_1), aio, aio, 0u);
    }
}

static void configure_resolutions(uint8_t channels, uint8_t high_resolution)
{
    uint8_t channel;

    AP_AON->PMCTL2_1 = 0u;
    for (channel = 2u; channel <= 7u; ++channel) {
        if (channels & BIT(channel)) {
            set_sampling_resolution((adc_CH_t)channel,
                                    (high_resolution & BIT(channel)) != 0u,
                                    false);
        }
    }
}

static void disable_analog_pin(adc_CH_t channel)
{
    gpio_pin_e pin;

    if ((uint8_t)channel >= ADC_CH_NUM) return;
    pin = s_pinmap[(uint8_t)channel];
    if (pin == GPIO_DUMMY) return;

    hal_gpio_cfg_analog_io(pin, Bit_DISABLE);
    hal_gpio_pin_init(pin, GPIO_INPUT);
    hal_gpio_pull_set(pin, GPIO_FLOATING);
}

static void enable_channel_register(adc_CH_t channel)
{
    switch (channel) {
    case ADC_CH1N_P11: AP_PCRM->ADC_CTL1 |= BIT(20); break;
    case ADC_CH1P_P23: AP_PCRM->ADC_CTL1 |= BIT(4); break;
    case ADC_CH2N_P24: AP_PCRM->ADC_CTL2 |= BIT(20); break;
    case ADC_CH2P_P14: AP_PCRM->ADC_CTL2 |= BIT(4); break;
    case ADC_CH3N_P15: AP_PCRM->ADC_CTL3 |= BIT(20); break;
    case ADC_CH3P_P20: AP_PCRM->ADC_CTL3 |= BIT(4); break;
    default: break;
    }
}

static void clear_channel_registers(void)
{
    AP_PCRM->ADC_CTL0 &= ~BIT(20);
    AP_PCRM->ADC_CTL0 &= ~BIT(4);
    AP_PCRM->ADC_CTL1 &= ~BIT(20);
    AP_PCRM->ADC_CTL1 &= ~BIT(4);
    AP_PCRM->ADC_CTL2 &= ~BIT(20);
    AP_PCRM->ADC_CTL2 &= ~BIT(4);
    AP_PCRM->ADC_CTL3 &= ~BIT(20);
    AP_PCRM->ADC_CTL3 &= ~BIT(4);
}

static void stop_conversion(void)
{
    uint8_t channel;
    uint8_t requested = s_adc.requested_channels;

    /* SDK 3.1.1 waited here until intr_status became zero, with its timeout
     * compiled out. Never poll an analog status register indefinitely in IRQ. */
    MASK_ADC_INT;
    NVIC_DisableIRQ((IRQn_Type)ADCC_IRQn);
    JUMP_FUNCTION(ADCC_IRQ_HANDLER) = 0u;
    AP_ADCC->intr_clear = 0x1ffu;

    AP_PCRM->ANA_CTL &= ~BIT(3);
    for (channel = 2u; channel <= 7u; ++channel) {
        if (requested & BIT(channel)) disable_analog_pin((adc_CH_t)channel);
    }
    AP_PCRM->ANA_CTL &= ~BIT(0);

    /* Match the fixed 3.1.2 shutdown path: reset and gate ADCC, but do not
     * repeatedly tear down the shared CLKHF/DLL source (CLKHF_CTL1 bit 13). */
    hal_clk_reset(MOD_ADCC);
    hal_clk_gate_disable(MOD_ADCC);

    clear_runtime_context();
    (void)hal_pwrmgr_unlock(MOD_ADCC);
    NVIC_ClearPendingIRQ((IRQn_Type)ADCC_IRQn);
}

void __attribute__((used)) hal_ADC_IRQHandler(void)
{
    static uint16_t samples[DPLS_ADC_RAW_SAMPLE_COUNT];
    uint32_t pending = AP_ADCC->intr_status & DPLS_ADC_STATUS_MASK;
    uint8_t status_channel;

    MASK_ADC_INT;
    pending &= s_adc.status_channels;

    for (status_channel = 2u; status_channel <= 7u; ++status_channel) {
        uint8_t logical;
        uint8_t index;

        if ((pending & BIT(status_channel)) == 0u) continue;

        /* Corrected SDK 3.1.2 extraction: each 32-bit register carries two
         * 12-bit samples. SDK 3.1.1 overwrote adjacent array entries. */
        for (index = 0u; index < (MAX_ADC_SAMPLE_SIZE - 2u); ++index) {
            uint32_t value = read_reg(ADC_CH_BASE +
                                      ((uint32_t)status_channel * 0x80u) +
                                      ((uint32_t)(index + 2u) * 4u));
            samples[(uint16_t)index * 2u] = (uint16_t)(value & 0x0fffu);
            samples[(uint16_t)index * 2u + 1u] = (uint16_t)((value >> 16) & 0x0fffu);
        }

        AP_ADCC->intr_clear = BIT(status_channel);
        s_adc.completed_status |= (uint8_t)BIT(status_channel);
        logical = (uint8_t)logical_channel_for_status(status_channel);

        if (s_adc.enabled && logical < ADC_CH_NUM && s_adc.handlers[logical]) {
            adc_Evt_t event;
            event.type = HAL_ADC_EVT_DATA;
            event.ch = (adc_CH_t)logical;
            event.data = samples;
            event.size = (uint8_t)DPLS_ADC_RAW_SAMPLE_COUNT;
            s_adc.handlers[logical](&event);
        }
    }

    if ((s_adc.completed_status & s_adc.status_channels) == s_adc.status_channels) {
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
    if (rc == PPlus_SUCCESS || rc == PPlus_ERR_INVALID_STATE) {
        s_adc.initialized = true;
    }
}

int hal_adc_clock_config(adc_CLOCK_SEL_t clock)
{
    if (!s_adc.initialized) return PPlus_ERR_NOT_REGISTED;
    subWriteReg(0x4000f07cu, 2, 1, clock);
    return PPlus_SUCCESS;
}

int hal_adc_config_channel(adc_Cfg_t cfg, adc_Hdl_t handler)
{
    uint8_t channel;

    if (!s_adc.initialized) return PPlus_ERR_NOT_REGISTED;
    if (s_adc.enabled) return PPlus_ERR_BUSY;
    if (!handler) return PPlus_ERR_INVALID_PARAM;
    if ((cfg.channel & DPLS_ADC_CHANNEL_MASK) == 0u ||
        (cfg.channel & (uint8_t)~DPLS_ADC_CHANNEL_MASK) != 0u ||
        cfg.is_differential_mode != 0u) {
        return PPlus_ERR_NOT_SUPPORTED;
    }

    clear_runtime_context();
    s_adc.continue_mode = cfg.is_continue_mode;
    s_adc.requested_channels = cfg.channel;
    s_adc.status_channels = status_mask_for_channels(cfg.channel);

    if ((AP_PCR->SW_CLK & BIT(MOD_ADCC)) == 0u) hal_clk_gate_enable(MOD_ADCC);

    AP_PCRM->CLKSEL |= BIT(6);       /* 1.28 MHz source */
    AP_PCRM->CLKHF_CTL0 |= BIT(18);  /* XTAL output for 32 MHz DLL */
    AP_PCRM->CLKHF_CTL1 |= BIT(7);   /* DLL enable */
    AP_PCRM->CLKHF_CTL1 &= ~BIT(21); /* no doubled ADC clock */
    AP_PCRM->CLKHF_CTL1 |= BIT(13);  /* ADC clock enable */
    AP_PCRM->ADC_CTL4 |= BIT(4);
    AP_PCRM->ADC_CTL4 |= BIT(0);

    configure_resolutions(cfg.channel, cfg.is_high_resolution);
    clear_channel_registers();
    AP_PCRM->ANA_CTL &= ~BIT(23); /* microphone bias off */
    AP_PCRM->ADC_CTL4 &= ~BIT(4); /* auto sampling mode */

    for (channel = 2u; channel <= 7u; ++channel) {
        if (cfg.channel & BIT(channel)) {
            gpio_pin_e pin = s_pinmap[channel];
            hal_gpio_pull_set(pin, GPIO_FLOATING);
            hal_gpio_ds_control(pin, Bit_ENABLE);
            hal_gpio_cfg_analog_io(pin, Bit_ENABLE);
            enable_channel_register((adc_CH_t)channel);
            s_adc.handlers[channel] = handler;
        }
    }

    return PPlus_SUCCESS;
}

int hal_adc_start(void)
{
    int rc;

    if (!s_adc.initialized) return PPlus_ERR_NOT_REGISTED;
    if (s_adc.requested_channels == 0u || s_adc.status_channels == 0u)
        return PPlus_ERR_INVALID_STATE;

    rc = hal_pwrmgr_lock(MOD_ADCC);
    if (rc != PPlus_SUCCESS) return rc;

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
    if (!s_adc.enabled) return PPlus_SUCCESS;
    stop_conversion();
    return PPlus_SUCCESS;
}

static void load_calibration(void)
{
    if (s_calibration_loaded) return;
    s_calibration_loaded = 1u;
    s_calibration_negative = (uint16_t)(read_reg(0x11001000u) & 0x0fffu);
    s_calibration_positive = (uint16_t)((read_reg(0x11001000u) >> 16) & 0x0fffu);

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
    uint32_t index;
    int32_t sum = 0;
    float result;

    if (!buffer || size == 0u || (uint8_t)channel >= ADC_CH_NUM) return 0.0f;

    for (index = 0u; index < size; ++index) sum += (int32_t)(buffer[index] & 0x0fffu);
    load_calibration();
    result = (float)sum / (float)size;

    if (s_calibration_positive != 0x0fffu && s_calibration_negative != 0x0fffu) {
        float delta = ((float)s_calibration_positive - (float)s_calibration_negative) / 2.0f;
        float denominator = (float)s_calibration_positive + (float)s_calibration_negative;
        if (((uint8_t)channel & 1u) != 0u) {
            result = differential_mode
                ? ((result - 2048.0f - delta) * 2.0f / denominator)
                : ((result - delta) / denominator);
        } else {
            result = differential_mode
                ? ((result - 2048.0f - delta) * 2.0f / denominator)
                : ((result + delta) / denominator);
        }
    } else {
        result = differential_mode ? (result / 2048.0f - 1.0f)
                                   : (result / 4096.0f);
    }

    if (high_resolution) result *= 0.8f;
    else result = result * (float)adc_lambda[(uint8_t)channel] * 0.8f / 1000000.0f;
    return result;
}
