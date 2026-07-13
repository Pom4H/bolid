#include "dpls_server.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    bool encrypted, initialized, normal, identify;
    dpls_power_t power_src;
    bool low_reserve;
    bool short_active;
    dpls_mode_t mode;
    uint8_t tx[DPLS_MAX_FRAME];
    size_t tx_len;
    uint8_t notif[64][DPLS_MAX_FRAME];
    size_t notif_len[64];
    unsigned apply_count, indication_count, notification_count;
    dpls_event_t stored[DPLS_EVENT_CAPACITY];
    uint16_t stored_count;
    uint32_t stored_next;
} fake_t;
static bool encrypted(void *c) { return ((fake_t *)c)->encrypted; }
static bool apply(void *c, dpls_mode_t m) { fake_t *f = c; f->normal = m == DPLS_MODE_NORMAL; f->mode = m; ++f->apply_count; return true; }
static void normal(void *c) { fake_t *f = c; f->normal = true; f->mode = DPLS_MODE_NORMAL; }
static uint16_t voltage(void *c) { (void)c; return 24100; }
static dpls_power_t power(void *c) { return ((fake_t *)c)->power_src; }
static bool low(void *c) { return ((fake_t *)c)->low_reserve; }
static bool real_short(void *c) { return ((fake_t *)c)->short_active; }
static void identify(void *c, bool on) { ((fake_t *)c)->identify = on; }
static bool random_bytes(void *c, uint8_t *p, size_t n) { (void)c; memset(p, 0x55, n); return true; }
static dpls_settings_state_t settings_state(void *c) { return ((fake_t *)c)->initialized ? DPLS_SETTINGS_VALID : DPLS_SETTINGS_EMPTY; }
static void salt(void *c, uint8_t p[16]) { (void)c; memset(p, 0x11, 16); }
static bool settings(void *c, const char *n, const uint8_t s[16], const uint8_t v[32]) { (void)n; (void)s; (void)v; ((fake_t *)c)->initialized = true; return true; }
static bool verify(void *c, const uint8_t d[16], const uint8_t n[16], uint32_t id, const uint8_t p[32]) { (void)c; (void)d; (void)n; (void)id; return p[0] == 0xa5; }
static bool indicate(void *c, const uint8_t *p, size_t n) {
    fake_t *f = c;
    if (f->notification_count < 64u) {
        memcpy(f->notif[f->notification_count], p, n);
        f->notif_len[f->notification_count] = n;
    }
    memcpy(f->tx, p, n);
    f->tx_len = n;
    ++f->indication_count;
    ++f->notification_count;
    return true;
}
static bool notify(void *c, const uint8_t *p, size_t n) {
    fake_t *f = c;
    if (f->notification_count < 64u) {
        memcpy(f->notif[f->notification_count], p, n);
        f->notif_len[f->notification_count] = n;
    }
    memcpy(f->tx, p, n);
    f->tx_len = n;
    ++f->notification_count;
    return true;
}
static bool storage_init(void *c, uint16_t *count, uint32_t *next) {
    fake_t *f = c; *count = f->stored_count; *next = f->stored_next ? f->stored_next : 1u; return true;
}
static bool storage_append(void *c, const dpls_event_t *event) {
    fake_t *f = c; f->stored[(event->sequence - 1u) % DPLS_EVENT_CAPACITY] = *event;
    if (f->stored_count < DPLS_EVENT_CAPACITY) ++f->stored_count;
    f->stored_next = event->sequence + 1u; return true;
}
static bool storage_read(void *c, uint32_t sequence, dpls_event_t *event) {
    fake_t *f = c; *event = f->stored[(sequence - 1u) % DPLS_EVENT_CAPACITY];
    return event->sequence == sequence;
}

static size_t request(uint8_t type, const uint8_t *p, uint16_t n, uint8_t *out) {
    dpls_frame_t f; memset(&f, 0, sizeof(f)); f.type = type; f.sequence = 1; f.payload_length = n; if (n) memcpy(f.payload, p, n);
    return dpls_frame_encode(&f, out, DPLS_MAX_FRAME);
}

int main(void) {
    fake_t fake = {.encrypted = true, .initialized = true};
    dpls_hal_t hal = {
        .link_encrypted = encrypted, .hardware_apply_mode = apply, .hardware_safe_normal = normal,
        .voltage_mv = voltage, .power_source = power, .reserve_low = low,
        .real_short_active = real_short, .identify_led = identify,
        .random_bytes = random_bytes, .settings_state = settings_state, .settings_salt = salt,
        .settings_write = settings, .verify_auth_proof = verify,
        .event_storage_init = storage_init, .event_storage_append = storage_append,
        .event_storage_read = storage_read,
        .tx_indicate = indicate, .tx_notify = notify, .context = &fake,
    };
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
    fake.notification_count = 0;
    n = request(DPLS_MSG_LOG_START, payload, 14, buf); dpls_server_receive(&server, buf, n, 60);
    assert(fake.notification_count == 1);
    assert(dpls_frame_decode(fake.notif[0], fake.notif_len[0], &response));
    assert(response.type == DPLS_MSG_LOG_INFO);
    payload[12] = 0; payload[13] = 0;
    fake.notification_count = 0;
    n = request(DPLS_MSG_LOG_ACK, payload, 14, buf); dpls_server_receive(&server, buf, n, 65);
    assert(fake.notification_count == 1);
    assert(dpls_frame_decode(fake.tx, fake.tx_len, &response));
    assert(response.type == DPLS_MSG_LOG_CHUNK);
    assert(response.payload[0] == 0 && response.payload[1] == 0);
    payload[12] = 1; payload[13] = 0;
    fake.notification_count = 0;
    n = request(DPLS_MSG_LOG_ACK, payload, 14, buf); dpls_server_receive(&server, buf, n, 70);
    assert(fake.notification_count == 1);
    assert(dpls_frame_decode(fake.tx, fake.tx_len, &response));
    assert(response.type == DPLS_MSG_LOG_CHUNK);
    assert(response.payload[0] == 1 && response.payload[1] == 0);
    unsigned indications_before = fake.indication_count;
    n = request(DPLS_MSG_IDENTIFY_START, NULL, 0, buf); dpls_server_receive(&server, buf, n, 80);
    assert(server.identify_active); assert(fake.identify);
    assert(fake.indication_count == indications_before);
    /* The core no longer toggles the LED for blinking: identify stays active
     * (and reported) until the deadline; the LED driver owns the blink. */
    dpls_server_tick(&server, 80 + DPLS_IDENTIFY_BLINK_MS);
    assert(server.identify_active); assert(fake.identify);
    dpls_server_tick(&server, 80 + DPLS_IDENTIFY_MAX_MS);
    assert(!server.identify_active); assert(!fake.identify);
    assert(fake.indication_count == indications_before);

    /* Power-source and reserve-low transitions are journaled once per edge. */
    dpls_event_t ev;
    uint32_t base = 80 + DPLS_IDENTIFY_MAX_MS;
    uint32_t seq = server.next_event_sequence;
    fake.power_src = DPLS_POWER_RESERVE;
    dpls_server_tick(&server, base + 10);
    assert(server.next_event_sequence == seq + 1);
    assert(storage_read(&fake, server.next_event_sequence - 1, &ev));
    assert(ev.event_type == 12 && ev.parameter == DPLS_POWER_RESERVE);
    seq = server.next_event_sequence;
    fake.low_reserve = true;
    dpls_server_tick(&server, base + 20);
    assert(server.next_event_sequence == seq + 1);
    assert(storage_read(&fake, server.next_event_sequence - 1, &ev));
    assert(ev.event_type == 13 && ev.parameter == 1);
    seq = server.next_event_sequence;      /* steady state logs nothing new */
    dpls_server_tick(&server, base + 30);
    assert(server.next_event_sequence == seq);
    fake.power_src = DPLS_POWER_LINE;
    fake.low_reserve = false;
    dpls_server_tick(&server, base + 40);  /* both clear -> two more events */
    assert(server.next_event_sequence == seq + 2);

    /* Auto-isolation of a real short: logged, and it forces an active test mode
     * back to Norma (priority over test modes). */
    seq = server.next_event_sequence;
    server.mode = DPLS_MODE_SHORT_2;       /* pretend a test mode is running */
    fake.short_active = true;
    dpls_server_tick(&server, base + 50);
    assert(server.real_short);
    assert(server.mode == DPLS_MODE_NORMAL);
    assert(server.next_event_sequence == seq + 2);
    assert(storage_read(&fake, seq, &ev) && ev.event_type == 14 && ev.parameter == 1);
    assert(storage_read(&fake, seq + 1, &ev) && ev.event_type == 8 &&
           ev.parameter == DPLS_RETURN_AUTO_ISOLATION);
    seq = server.next_event_sequence;
    fake.short_active = false;             /* short cleared -> one event, param 0 */
    dpls_server_tick(&server, base + 60);
    assert(!server.real_short);
    assert(server.next_event_sequence == seq + 1);
    assert(storage_read(&fake, seq, &ev) && ev.event_type == 14 && ev.parameter == 0);

    puts("test_dpls_server: OK"); return 0;
}
