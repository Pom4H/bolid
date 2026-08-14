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

/* Product behaviour: one configured channel per conversion, so the equality
 * check always matches when that channel completes. */
static void sequential_irq(adc_model_t *adc, uint8_t status)
{
    vendor_irq(adc, status);
}

/* Candidate dual-channel fix: accumulate completed channel flags. */
static void fixed_dual_irq(adc_model_t *adc, uint8_t status)
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

    sequential_irq(&adc, 0x80u);
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

static void test_sequential_dual_channel_completes(void)
{
    adc_model_t line = {.all_channels = 0x80u};
    adc_model_t vcap = {.all_channels = 0x08u};

    sequential_irq(&line, 0x80u);
    assert(line.cleared_channels == 0x80u);
    assert(line.stopped);

    sequential_irq(&vcap, 0x08u);
    assert(vcap.cleared_channels == 0x08u);
    assert(vcap.stopped);
}

static void test_fixed_dual_channel_staggered_completes(void)
{
    adc_model_t adc = {.all_channels = 0x88u};

    fixed_dual_irq(&adc, 0x08u);
    assert(adc.cleared_channels == 0x08u);
    assert(!adc.stopped);

    fixed_dual_irq(&adc, 0x80u);
    assert(adc.cleared_channels == 0x88u);
    assert(adc.done_channels == 0x88u);
    assert(adc.stopped);
}

int main(void)
{
    test_single_channel();
    test_vendor_dual_channel_staggered_wedges();
    test_sequential_dual_channel_completes();
    test_fixed_dual_channel_staggered_completes();
    puts("ADC IRQ model: sequential sampling avoids staggered dual-channel wedge");
    return 0;
}
