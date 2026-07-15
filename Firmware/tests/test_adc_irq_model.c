#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>

typedef struct {
    uint8_t all_channels;
    uint8_t done_channels;
    uint8_t cleared_channels;
    bool stopped;
} adc_model_t;

/* Mirrors the relevant PHY62XX SDK 3.1.2 ISR condition: process, clear and
 * stop only when the current IRQ status exactly equals all configured channels. */
static void vendor_irq(adc_model_t *adc, uint8_t status)
{
    if (status == adc->all_channels) {
        adc->cleared_channels |= status;
        adc->stopped = true;
    }
}

/* Candidate behaviour: process every completed channel and accumulate the
 * completion mask until all configured channels have finished. */
static void fixed_irq(adc_model_t *adc, uint8_t status)
{
    uint8_t pending = status & adc->all_channels;

    if (pending == 0u) {
        return;
    }

    adc->cleared_channels |= pending;
    adc->done_channels |= pending;
    if ((adc->done_channels & adc->all_channels) == adc->all_channels) {
        adc->stopped = true;
    }
}

static void test_single_channel(void)
{
    adc_model_t adc = {.all_channels = 0x80u};

    vendor_irq(&adc, 0x80u);
    assert(adc.cleared_channels == 0x80u);
    assert(adc.stopped);
}

static void test_vendor_dual_channel_staggered_wedges(void)
{
    adc_model_t adc = {.all_channels = 0x88u};

    /* P23 completes before P20. The SDK equality condition neither processes
     * nor clears the first channel, so the same pending IRQ can immediately
     * re-enter before the second channel completes. */
    vendor_irq(&adc, 0x08u);
    assert(adc.cleared_channels == 0u);
    assert(!adc.stopped);

    vendor_irq(&adc, 0x08u);
    assert(adc.cleared_channels == 0u);
    assert(!adc.stopped);
}

static void test_fixed_dual_channel_staggered_completes(void)
{
    adc_model_t adc = {.all_channels = 0x88u};

    fixed_irq(&adc, 0x08u);
    assert(adc.cleared_channels == 0x08u);
    assert(!adc.stopped);

    fixed_irq(&adc, 0x80u);
    assert(adc.cleared_channels == 0x88u);
    assert(adc.done_channels == 0x88u);
    assert(adc.stopped);
}

int main(void)
{
    test_single_channel();
    test_vendor_dual_channel_staggered_wedges();
    test_fixed_dual_channel_staggered_completes();
    puts("ADC IRQ model: staggered-completion wedge reproduced");
    return 0;
}
