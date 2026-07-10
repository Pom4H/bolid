#include "dpls_server.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    bool encrypted, initialized, normal, identify;
    dpls_mode_t mode;
    uint8_t tx[DPLS_MAX_FRAME];
    size_t tx_len;
    unsigned apply_count, indication_count, notification_count;
} fake_t;
static bool encrypted(void *c) { return ((fake_t *)c)->encrypted; }
static bool apply(void *c, dpls_mode_t m) { fake_t *f = c; f->normal = m == DPLS_MODE_NORMAL; f->mode = m; ++f->apply_count; return true; }
static void normal(void *c) { fake_t *f = c; f->normal = true; f->mode = DPLS_MODE_NORMAL; }
static uint16_t voltage(void *c) { (void)c; return 24100; }
static dpls_power_t power(void *c) { (void)c; return DPLS_POWER_LINE; }
static bool low(void *c) { (void)c; return false; }
static void identify(void *c, bool on) { ((fake_t *)c)->identify = on; }
static void random_bytes(void *c, uint8_t *p, size_t n) { (void)c; memset(p, 0x55, n); }
static bool initialized(void *c) { return ((fake_t *)c)->initialized; }
static void salt(void *c, uint8_t p[16]) { (void)c; memset(p, 0x11, 16); }
static bool settings(void *c, const char *n, const uint8_t s[16], const uint8_t v[32]) { (void)n; (void)s; (void)v; ((fake_t *)c)->initialized = true; return true; }
static bool verify(void *c, const uint8_t d[16], const uint8_t n[16], uint32_t id, const uint8_t p[32]) { (void)c; (void)d; (void)n; (void)id; return p[0] == 0xa5; }
static bool indicate(void *c, const uint8_t *p, size_t n) {
    fake_t *f = c; memcpy(f->tx, p, n); f->tx_len = n; ++f->indication_count; return true;
}
static bool notify(void *c, const uint8_t *p, size_t n) {
    fake_t *f = c; memcpy(f->tx, p, n); f->tx_len = n; ++f->notification_count; return true;
}

static size_t request(uint8_t type, const uint8_t *p, uint16_t n, uint8_t *out) {
    dpls_frame_t f; memset(&f, 0, sizeof(f)); f.type = type; f.sequence = 1; f.payload_length = n; if (n) memcpy(f.payload, p, n);
    return dpls_frame_encode(&f, out, DPLS_MAX_FRAME);
}

int main(void) {
    fake_t fake = {.encrypted = true, .initialized = true};
    dpls_hal_t hal = {encrypted, apply, normal, voltage, power, low, identify, random_bytes, initialized, salt, settings, verify, 0, indicate, notify, &fake};
    dpls_server_t server; uint8_t buf[DPLS_MAX_FRAME], payload[64]; size_t n; dpls_frame_t response;
    dpls_server_init(&server, &hal, 0); assert(fake.normal);
    dpls_server_connected(&server, 10);
    memset(payload, 1, 16); n = request(DPLS_MSG_HELLO, payload, 16, buf); assert(dpls_server_receive(&server, buf, n, 20));
    assert(dpls_frame_decode(fake.tx, fake.tx_len, &response)); assert(response.type == DPLS_MSG_AUTH_CHALLENGE);
    memset(payload, 0, 48); payload[16] = 0xa5; n = request(DPLS_MSG_AUTH_PROOF, payload, 48, buf); dpls_server_receive(&server, buf, n, 30); assert(server.authenticated);
    memcpy(payload, &server.session_id, 4); memcpy(payload + 4, server.session_token, 8); payload[12] = 7; payload[13] = payload[14] = payload[15] = 0; payload[16] = DPLS_MODE_SHORT_1;
    n = request(DPLS_MSG_MODE_SET, payload, 17, buf); dpls_server_receive(&server, buf, n, 40); assert(server.mode == DPLS_MODE_SHORT_1); assert(fake.apply_count == 1);
    dpls_server_receive(&server, buf, n, 50); assert(fake.apply_count == 1); /* idempotent */
    dpls_server_tick(&server, DPLS_SESSION_TIMEOUT_MS + 51); assert(server.mode == DPLS_MODE_NORMAL); assert(fake.normal);
    memcpy(payload, &server.session_id, 4); memcpy(payload + 4, server.session_token, 8); payload[12] = payload[13] = 0;
    n = request(DPLS_MSG_LOG_START, payload, 14, buf); dpls_server_receive(&server, buf, n, 60);
    assert(fake.notification_count == 1); assert(dpls_frame_decode(fake.tx, fake.tx_len, &response));
    assert(response.type == DPLS_MSG_LOG_CHUNK); assert(response.payload[0] == 0 && response.payload[1] == 0);
    payload[12] = 1; payload[13] = 0;
    n = request(DPLS_MSG_LOG_ACK, payload, 14, buf); dpls_server_receive(&server, buf, n, 70);
    assert(fake.notification_count == 2); assert(dpls_frame_decode(fake.tx, fake.tx_len, &response));
    assert(response.type == DPLS_MSG_LOG_CHUNK); assert(response.payload[0] == 1 && response.payload[1] == 0);
    puts("test_dpls_server: OK"); return 0;
}
