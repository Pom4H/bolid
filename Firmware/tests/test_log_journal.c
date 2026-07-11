#include "dpls_server.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

enum { TST_EVT_BLE_CONNECTED = 2 };

typedef struct {
    bool encrypted, initialized, normal, identify;
    dpls_mode_t mode;
    uint8_t tx[DPLS_MAX_FRAME];
    size_t tx_len;
    uint8_t notif[256][DPLS_MAX_FRAME];
    size_t notif_len[256];
    unsigned notification_count;
} fake_t;

static bool encrypted(void *c) { return ((fake_t *)c)->encrypted; }
static bool apply(void *c, dpls_mode_t m) { fake_t *f = c; f->normal = m == DPLS_MODE_NORMAL; f->mode = m; return true; }
static void normal(void *c) { fake_t *f = c; f->normal = true; f->mode = DPLS_MODE_NORMAL; }
static uint16_t voltage(void *c) { (void)c; return 24100; }
static dpls_power_t power(void *c) { (void)c; return DPLS_POWER_LINE; }
static bool low(void *c) { (void)c; return false; }
static void identify(void *c, bool on) { ((fake_t *)c)->identify = on; }
static bool random_bytes(void *c, uint8_t *p, size_t n) { (void)c; memset(p, 0x55, n); return true; }
static dpls_settings_state_t settings_state(void *c) { return ((fake_t *)c)->initialized ? DPLS_SETTINGS_VALID : DPLS_SETTINGS_EMPTY; }
static void salt(void *c, uint8_t p[16]) { (void)c; memset(p, 0x11, 16); }
static bool settings(void *c, const char *n, const uint8_t s[16], const uint8_t v[32]) { (void)n; (void)s; (void)v; ((fake_t *)c)->initialized = true; return true; }
static bool verify(void *c, const uint8_t d[16], const uint8_t n[16], uint32_t id, const uint8_t p[32]) { (void)c; (void)d; (void)n; (void)id; return p[0] == 0xa5; }
static bool notify(void *c, const uint8_t *p, size_t n) {
    fake_t *f = c;
    if (f->notification_count < 256u) {
        memcpy(f->notif[f->notification_count], p, n);
        f->notif_len[f->notification_count] = n;
    }
    memcpy(f->tx, p, n);
    f->tx_len = n;
    ++f->notification_count;
    return true;
}
static bool indicate(void *c, const uint8_t *p, size_t n) { return notify(c, p, n); }

static size_t request(uint8_t type, const uint8_t *p, uint16_t n, uint8_t *out) {
    dpls_frame_t f;
    memset(&f, 0, sizeof(f));
    f.type = type;
    f.sequence = 1;
    f.payload_length = n;
    if (n) memcpy(f.payload, p, n);
    return dpls_frame_encode(&f, out, DPLS_MAX_FRAME);
}

static void auth_session(dpls_server_t *s, fake_t *fake, uint8_t *buf) {
    uint8_t payload[64];
    size_t n;
    (void)fake;
    dpls_server_connected(s, 10);
    memset(payload, 1, 16);
    n = request(DPLS_MSG_HELLO, payload, 16, buf);
    assert(dpls_server_receive(s, buf, n, 20));
    memset(payload, 0, 48);
    payload[16] = 0xa5;
    n = request(DPLS_MSG_AUTH_PROOF, payload, 48, buf);
    assert(dpls_server_receive(s, buf, n, 30));
    assert(s->authenticated);
}

static uint16_t export_events(dpls_server_t *s, fake_t *fake, uint8_t *buf, dpls_event_t *out, uint16_t max_out) {
    uint8_t payload[64];
    dpls_frame_t frame;
    size_t n;
    uint16_t count = 0;
    uint16_t next = 0;

    memcpy(payload, &s->session_id, 4);
    memcpy(payload + 4, s->session_token, 8);
    payload[12] = payload[13] = 0;
    fake->notification_count = 0;
    n = request(DPLS_MSG_LOG_START, payload, 14, buf);
    assert(dpls_server_receive(s, buf, n, s->now_ms));
    assert(fake->notification_count == 1);
    assert(dpls_frame_decode(fake->notif[0], fake->notif_len[0], &frame));
    assert(frame.type == DPLS_MSG_LOG_INFO);
    next = 0;
    while (count < max_out) {
        unsigned before = fake->notification_count;
        memcpy(payload, &s->session_id, 4);
        memcpy(payload + 4, s->session_token, 8);
        payload[12] = (uint8_t)next;
        payload[13] = (uint8_t)(next >> 8);
        n = request(DPLS_MSG_LOG_ACK, payload, 14, buf);
        assert(dpls_server_receive(s, buf, n, s->now_ms));
        assert(fake->notification_count > before);
        assert(dpls_frame_decode(fake->notif[fake->notification_count - 1], fake->notif_len[fake->notification_count - 1], &frame));
        if (frame.type == DPLS_MSG_LOG_RESULT) break;
        assert(frame.type == DPLS_MSG_LOG_CHUNK);
        out[count].sequence = (uint32_t)frame.payload[2] | ((uint32_t)frame.payload[3] << 8) |
                              ((uint32_t)frame.payload[4] << 16) | ((uint32_t)frame.payload[5] << 24);
        out[count].timestamp_seconds = (uint32_t)frame.payload[6] | ((uint32_t)frame.payload[7] << 8) |
                                       ((uint32_t)frame.payload[8] << 16) | ((uint32_t)frame.payload[9] << 24);
        out[count].event_type = frame.payload[10];
        out[count].parameter = frame.payload[11];
        ++count;
        next = (uint16_t)(frame.payload[0] | (frame.payload[1] << 8)) + 1u;
    }
    return count;
}

static void test_invalid_type_not_stored(void) {
    fake_t fake = {.encrypted = true, .initialized = true};
    dpls_hal_t hal = {
        .link_encrypted = encrypted, .hardware_apply_mode = apply, .hardware_safe_normal = normal,
        .voltage_mv = voltage, .power_source = power, .reserve_low = low, .identify_led = identify,
        .random_bytes = random_bytes, .settings_state = settings_state, .settings_salt = salt,
        .settings_write = settings, .verify_auth_proof = verify,
        .tx_indicate = indicate, .tx_notify = notify, .context = &fake,
    };
    dpls_server_t server;
    uint16_t before;

    dpls_server_init(&server, &hal, 0);
    before = server.event_count;
    dpls_server_log(&server, 0, 0);
    dpls_server_log(&server, 99, 0);
    assert(server.event_count == before);
}

static void test_ring_overflow(void) {
    fake_t fake = {.encrypted = true, .initialized = true};
    dpls_hal_t hal = {
        .link_encrypted = encrypted, .hardware_apply_mode = apply, .hardware_safe_normal = normal,
        .voltage_mv = voltage, .power_source = power, .reserve_low = low, .identify_led = identify,
        .random_bytes = random_bytes, .settings_state = settings_state, .settings_salt = salt,
        .settings_write = settings, .verify_auth_proof = verify,
        .tx_indicate = indicate, .tx_notify = notify, .context = &fake,
    };
    dpls_server_t server;
    uint8_t buf[DPLS_MAX_FRAME];
    dpls_event_t exported[DPLS_EVENT_CAPACITY];
    uint16_t n, i;
    uint32_t expected_first_seq;
    unsigned extra = 50u;

    dpls_server_init(&server, &hal, 0);
    auth_session(&server, &fake, buf);
    expected_first_seq = server.next_event_sequence + extra;
    for (i = 0; i < DPLS_EVENT_CAPACITY + extra; ++i) {
        server.now_ms = (uint32_t)((i + 1u) * 1000u);
        dpls_server_log(&server, TST_EVT_BLE_CONNECTED, 0);
    }
    assert(server.event_count == DPLS_EVENT_CAPACITY);

    server.now_ms = (uint32_t)((DPLS_EVENT_CAPACITY + extra) * 1000u);
    fake.notification_count = 0;
    n = export_events(&server, &fake, buf, exported, DPLS_EVENT_CAPACITY);
    assert(n == server.event_count);
    assert(n == DPLS_EVENT_CAPACITY);
    assert(exported[0].sequence == expected_first_seq);
    for (i = 0; i < n; ++i) {
        assert(exported[i].event_type >= 1 && exported[i].event_type <= 11);
        assert(exported[i].sequence > 0);
        if (i > 0) assert(exported[i].sequence > exported[i - 1].sequence);
    }
}

int main(void) {
    test_invalid_type_not_stored();
    test_ring_overflow();
    printf("test_log_journal: OK (capacity=%u)\n", (unsigned)DPLS_EVENT_CAPACITY);
    return 0;
}
