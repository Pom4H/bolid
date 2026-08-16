#include "dpls_server.h"
#include <assert.h>
#include <string.h>


typedef struct {
    bool encrypted;
    bool led;
    unsigned led_calls;
    unsigned order;
    unsigned led_order;
    unsigned tx_order;
    uint8_t tx[DPLS_MAX_FRAME];
    size_t tx_len;
} fake_t;

static bool encrypted(void *context) { return ((fake_t *)context)->encrypted; }
static void safe_normal(void *context) { (void)context; }
static dpls_power_t power_source(void *context) { (void)context; return DPLS_POWER_LINE; }
static bool false_input(void *context) { (void)context; return false; }

static void identify_led(void *context, bool enabled)
{
    fake_t *fake = context;
    fake->led = enabled;
    ++fake->led_calls;
    if (enabled && fake->led_order == 0u) fake->led_order = ++fake->order;
}

static bool indicate(void *context, const uint8_t *frame, size_t length)
{
    fake_t *fake = context;
    assert(length <= sizeof(fake->tx));
    assert(fake->led);
    fake->tx_order = ++fake->order;
    memcpy(fake->tx, frame, length);
    fake->tx_len = length;
    return true;
}

static dpls_hal_t make_hal(fake_t *fake)
{
    dpls_hal_t hal;
    memset(&hal, 0, sizeof(hal));
    hal.context = fake;
    hal.link.encrypted = encrypted;
    hal.link.indicate = indicate;
    hal.hardware.safe_normal = safe_normal;
    hal.hardware.power_source = power_source;
    hal.hardware.reserve_low = false_input;
    hal.hardware.real_short_active = false_input;
    hal.hardware.identify_led = identify_led;
    return hal;
}

static size_t identify_request(uint16_t sequence, uint8_t out[DPLS_MAX_FRAME])
{
    dpls_frame_t frame;
    memset(&frame, 0, sizeof(frame));
    frame.type = DPLS_MSG_IDENTIFY_START;
    frame.flags = DPLS_FLAG_REQUEST;
    frame.sequence = sequence;
    return dpls_frame_encode(&frame, out, DPLS_MAX_FRAME);
}

int main(void)
{
    fake_t fake;
    dpls_hal_t hal;
    dpls_server_t server;
    dpls_frame_t ack;
    uint8_t request[DPLS_MAX_FRAME];
    const uint16_t sequence = 0x1234u;
    const uint32_t started_ms = 10u;
    size_t request_len;

    memset(&fake, 0, sizeof(fake));
    fake.encrypted = true;
    hal = make_hal(&fake);
    dpls_server_init(&server, &hal, 0u);
    dpls_server_connected(&server, 1u);

    request_len = identify_request(sequence, request);
    assert(request_len == DPLS_PROTOCOL_OVERHEAD);
    assert(dpls_server_receive(&server, request, request_len, started_ms));

    assert(fake.led);
    assert(fake.led_calls == 1u);
    assert(fake.led_order != 0u && fake.led_order < fake.tx_order);
    assert(fake.tx_len == DPLS_PROTOCOL_OVERHEAD);
    assert(dpls_frame_decode(fake.tx, fake.tx_len, &ack));
    assert(ack.type == DPLS_MSG_IDENTIFY_START);
    assert(ack.flags == DPLS_FLAG_RESPONSE);
    assert(ack.sequence == sequence);
    assert(ack.payload_length == 0u);

    dpls_server_tick(&server, started_ms + DPLS_IDENTIFY_MAX_MS - 1u);
    assert(fake.led);
    dpls_server_tick(&server, started_ms + DPLS_IDENTIFY_MAX_MS);
    assert(!fake.led);
    assert(fake.led_calls == 2u);
    return 0;
}
