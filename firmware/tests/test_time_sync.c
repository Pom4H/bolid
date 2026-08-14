#include "dpls_server.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    dpls_event_t events[16];
    uint16_t event_count;
    uint8_t tx[DPLS_MAX_FRAME];
    size_t tx_len;
} fake_t;

static void safe_normal(void *context) { (void)context; }
static dpls_power_t power_source(void *context) { (void)context; return DPLS_POWER_LINE; }
static bool reserve_low(void *context) { (void)context; return false; }
static bool storage_init(void *context, uint16_t *count, uint32_t *next_sequence) {
    fake_t *fake = context;
    *count = fake->event_count;
    *next_sequence = fake->event_count + 1u;
    return true;
}
static bool storage_append(void *context, const dpls_event_t *event) {
    fake_t *fake = context;
    assert(fake->event_count < 16u);
    fake->events[fake->event_count++] = *event;
    return true;
}
static bool indicate(void *context, const uint8_t *frame, size_t length) {
    fake_t *fake = context;
    assert(length <= sizeof(fake->tx));
    memcpy(fake->tx, frame, length);
    fake->tx_len = length;
    return true;
}
static bool notify(void *context, const uint8_t *frame, size_t length) {
    return indicate(context, frame, length);
}

static dpls_hal_t fake_hal(fake_t *fake) {
    dpls_hal_t hal;
    memset(&hal, 0, sizeof(hal));
    hal.hardware_safe_normal = safe_normal;
    hal.power_source = power_source;
    hal.reserve_low = reserve_low;
    hal.event_storage_init = storage_init;
    hal.event_storage_append = storage_append;
    hal.tx_indicate = indicate;
    hal.tx_notify = notify;
    hal.context = fake;
    return hal;
}

static size_t request(uint8_t type, const uint8_t *payload, uint16_t length, uint8_t *out) {
    dpls_frame_t frame;
    memset(&frame, 0, sizeof(frame));
    frame.type = type;
    frame.sequence = 1u;
    frame.payload_length = length;
    if (length) memcpy(frame.payload, payload, length);
    return dpls_frame_encode(&frame, out, DPLS_MAX_FRAME);
}

static void wr32(uint8_t *p, uint32_t value) {
    p[0] = (uint8_t)value;
    p[1] = (uint8_t)(value >> 8);
    p[2] = (uint8_t)(value >> 16);
    p[3] = (uint8_t)(value >> 24);
}

static void authorize(dpls_server_t *server) {
    static const uint8_t token[DPLS_SESSION_TOKEN_SIZE] = {1, 2, 3, 4, 5, 6, 7, 8};
    server->connected = true;
    server->authenticated = true;
    server->session_id = 0x78563412u;
    memcpy(server->session_token, token, sizeof(token));
}

static size_t time_sync_request(dpls_server_t *server, uint32_t unix_seconds, uint8_t *frame) {
    uint8_t payload[16];
    wr32(payload, server->session_id);
    memcpy(payload + 4, server->session_token, DPLS_SESSION_TOKEN_SIZE);
    wr32(payload + 12, unix_seconds);
    return request(DPLS_MSG_TIME_SYNC, payload, sizeof(payload), frame);
}

static void test_utc_is_ram_only_and_journal_uses_it(void) {
    const uint32_t utc = 1786732800u;
    fake_t fake;
    dpls_server_t server;
    dpls_hal_t hal;
    uint8_t frame[DPLS_MAX_FRAME];
    size_t length;

    memset(&fake, 0, sizeof(fake));
    hal = fake_hal(&fake);
    dpls_server_init(&server, &hal, 0u);
    assert(fake.event_count == 1u);                 /* BOOT */
    assert(fake.events[0].timestamp_seconds == 0u); /* no wall clock yet */

    authorize(&server);
    length = time_sync_request(&server, utc, frame);
    assert(dpls_server_receive(&server, frame, length, 5000u));
    assert(server.wall_clock_valid);
    assert(server.wall_clock_unix_seconds == utc);
    assert(fake.event_count == 1u); /* TIME_SYNC itself performs no NV journal write */

    dpls_server_tick(&server, 7500u);
    dpls_server_log(&server, 2u, 0u);
    assert(fake.events[1].timestamp_seconds == utc + 2u);

    dpls_server_tick(&server, 8500u);
    dpls_server_log(&server, 3u, 0u);
    assert(fake.events[2].timestamp_seconds == utc + 3u);
}

static void test_wall_clock_survives_now_ms_wrap(void) {
    const uint32_t utc = 1786732800u;
    fake_t fake;
    dpls_server_t server;
    dpls_hal_t hal;
    uint8_t frame[DPLS_MAX_FRAME];
    size_t length;

    memset(&fake, 0, sizeof(fake));
    hal = fake_hal(&fake);
    dpls_server_init(&server, &hal, 0xfffff000u);
    authorize(&server);
    length = time_sync_request(&server, utc, frame);
    assert(dpls_server_receive(&server, frame, length, 0xffffff00u));

    /* 0xffffff00 -> 1000 is 1256 ms across the uint32_t wrap. */
    dpls_server_tick(&server, 1000u);
    dpls_server_log(&server, 2u, 0u);
    assert(fake.events[fake.event_count - 1u].timestamp_seconds == utc + 1u);
}

static void test_invalid_or_unauthenticated_sync_is_rejected(void) {
    fake_t fake;
    dpls_server_t server;
    dpls_hal_t hal;
    dpls_frame_t response;
    uint8_t frame[DPLS_MAX_FRAME];
    size_t length;

    memset(&fake, 0, sizeof(fake));
    hal = fake_hal(&fake);
    dpls_server_init(&server, &hal, 0u);
    authorize(&server);

    length = time_sync_request(&server, DPLS_TIME_MIN_UNIX_SECONDS - 1u, frame);
    assert(dpls_server_receive(&server, frame, length, 1000u));
    assert(!server.wall_clock_valid);
    assert(dpls_frame_decode(fake.tx, fake.tx_len, &response));
    assert(response.type == DPLS_MSG_ERROR && response.payload[0] == 3u);

    server.authenticated = false;
    length = time_sync_request(&server, DPLS_TIME_MIN_UNIX_SECONDS, frame);
    assert(dpls_server_receive(&server, frame, length, 2000u));
    assert(!server.wall_clock_valid);
    assert(dpls_frame_decode(fake.tx, fake.tx_len, &response));
    assert(response.type == DPLS_MSG_ERROR && response.payload[0] == 2u);
}

int main(void) {
    test_utc_is_ram_only_and_journal_uses_it();
    test_wall_clock_survives_now_ms_wrap();
    test_invalid_or_unauthenticated_sync_is_rejected();
    puts("time sync tests passed");
    return 0;
}
