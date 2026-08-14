#include "dpls_server.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    bool encrypted, initialized, normal, identify;
    dpls_power_t power_src;
    bool low_reserve;
    bool short_active;
    uint8_t validity;
    bool auth_locked;
    bool lock_write_ok;
    bool apply_ok;
    bool random_ok;
    bool storage_init_ok;
    bool storage_append_ok;
    bool storage_read_ok;
    bool settings_write_ok;
    bool set_name_ok;
    bool set_password_ok;
    bool diagnostic_critical;
    unsigned diagnostic_count;
    unsigned disconnect_count;
    char name[32];
    dpls_mode_t mode;
    uint8_t tx[DPLS_MAX_FRAME];
    size_t tx_len;
    unsigned apply_count;
    dpls_event_t stored[DPLS_EVENT_CAPACITY];
    uint16_t stored_count;
    uint32_t stored_next;
} fake_t;

static bool encrypted(void *c) { return ((fake_t *)c)->encrypted; }
static bool apply(void *c, dpls_mode_t m) {
    fake_t *f = c;
    ++f->apply_count;
    if (!f->apply_ok) return false;
    f->normal = m == DPLS_MODE_NORMAL;
    f->mode = m;
    return true;
}
static void normal(void *c) { fake_t *f = c; f->normal = true; f->mode = DPLS_MODE_NORMAL; }
static uint16_t voltage(void *c) { (void)c; return 24100; }
static uint16_t port1_voltage(void *c) { (void)c; return 24100; }
static uint16_t port2_voltage(void *c) { (void)c; return 23800; }
static uint16_t port_t_voltage(void *c) { (void)c; return 23700; }
static uint16_t reserve_voltage(void *c) { (void)c; return 4200; }
static dpls_power_t power(void *c) { return ((fake_t *)c)->power_src; }
static bool low(void *c) { return ((fake_t *)c)->low_reserve; }
static uint8_t validity(void *c) { return ((fake_t *)c)->validity; }
static bool real_short(void *c) { return ((fake_t *)c)->short_active; }
static void identify(void *c, bool on) { ((fake_t *)c)->identify = on; }
static bool random_bytes(void *c, uint8_t *p, size_t n) {
    fake_t *f = c;
    if (!f->random_ok) return false;
    memset(p, 0x55, n);
    return true;
}
static dpls_settings_state_t settings_state(void *c) {
    return ((fake_t *)c)->initialized ? DPLS_SETTINGS_VALID : DPLS_SETTINGS_EMPTY;
}
static void salt(void *c, uint8_t p[16]) { (void)c; memset(p, 0x11, 16); }
static bool settings(void *c, const char *n, const uint8_t s[16], const uint8_t v[32]) {
    (void)n; (void)s; (void)v;
    fake_t *f = c;
    if (!f->settings_write_ok) return false;
    f->initialized = true;
    return true;
}
static bool verify(void *c, const uint8_t d[16], const uint8_t n[16], uint32_t id, const uint8_t p[32]) {
    (void)c; (void)d; (void)n; (void)id; return p[0] == 0xa5;
}
static bool indicate(void *c, const uint8_t *p, size_t n) {
    fake_t *f = c; memcpy(f->tx, p, n); f->tx_len = n; return true;
}
static bool notify(void *c, const uint8_t *p, size_t n) { return indicate(c, p, n); }
static bool storage_init(void *c, uint16_t *count, uint32_t *next) {
    fake_t *f = c;
    if (!f->storage_init_ok) return false;
    *count = f->stored_count;
    *next = f->stored_next ? f->stored_next : 1u;
    return true;
}
static bool storage_append(void *c, const dpls_event_t *event) {
    fake_t *f = c;
    if (!f->storage_append_ok) return false;
    f->stored[(event->sequence - 1u) % DPLS_EVENT_CAPACITY] = *event;
    if (f->stored_count < DPLS_EVENT_CAPACITY) ++f->stored_count;
    f->stored_next = event->sequence + 1u;
    return true;
}
static bool storage_read(void *c, uint32_t sequence, dpls_event_t *event) {
    fake_t *f = c;
    if (!f->storage_read_ok) return false;
    *event = f->stored[(sequence - 1u) % DPLS_EVENT_CAPACITY];
    return event->sequence == sequence;
}
static bool lock_read(void *c) { return ((fake_t *)c)->auth_locked; }
static bool lock_write(void *c, bool locked) {
    fake_t *f = c;
    if (!f->lock_write_ok) return false;
    f->auth_locked = locked;
    return true;
}
static void get_name(void *c, char out[DPLS_NAME_MAX + 1u]) {
    fake_t *f = c; memcpy(out, f->name, DPLS_NAME_MAX); out[DPLS_NAME_MAX] = '\0';
}
static bool set_name(void *c, const char *n) {
    fake_t *f = c;
    if (!f->set_name_ok) return false;
    size_t i = 0; while (i < 31u && n[i]) { f->name[i] = n[i]; ++i; } f->name[i] = '\0';
    return true;
}
static bool set_password(void *c, const uint8_t s[16], const uint8_t v[32]) {
    fake_t *f = c; (void)s; (void)v;
    if (!f->set_password_ok) return false;
    return true;
}
static void dev_info(void *c, dpls_device_info_t *out) {
    (void)c;
    out->device_id = 0x11u;
    out->fw_major = DPLS_FW_VERSION_MAJOR;
    out->fw_minor = DPLS_FW_VERSION_MINOR;
    out->fw_patch = DPLS_FW_VERSION_PATCH;
    out->hw_revision = 2;
    out->capabilities = DPLS_CAP_ADC_PRESENT | DPLS_CAP_MULTI_VOLTAGE_REPORT;
}
static void diagnostic(void *c, bool critical) {
    fake_t *f = c; ++f->diagnostic_count; f->diagnostic_critical = critical;
}
static void disconnect_after(void *c) { ++((fake_t *)c)->disconnect_count; }

static size_t request(uint8_t type, const uint8_t *p, uint16_t n, uint8_t *out) {
    dpls_frame_t f; memset(&f, 0, sizeof(f));
    f.type = type; f.sequence = 1; f.payload_length = n;
    if (n) memcpy(f.payload, p, n);
    return dpls_frame_encode(&f, out, DPLS_MAX_FRAME);
}

static fake_t make_fake(void) {
    fake_t f;
    memset(&f, 0, sizeof(f));
    f.encrypted = true;
    f.initialized = true;
    f.lock_write_ok = true;
    f.apply_ok = true;
    f.random_ok = true;
    f.storage_init_ok = true;
    f.storage_append_ok = true;
    f.storage_read_ok = true;
    f.settings_write_ok = true;
    f.set_name_ok = true;
    f.set_password_ok = true;
    memcpy(f.name, "Kit", 4);
    return f;
}

static dpls_hal_t make_hal(fake_t *f) {
    dpls_hal_t hal;
    memset(&hal, 0, sizeof(hal));
    hal.link_encrypted = encrypted;
    hal.hardware_apply_mode = apply;
    hal.hardware_safe_normal = normal;
    hal.voltage_mv = voltage;
    hal.port1_voltage_mv = port1_voltage;
    hal.port2_voltage_mv = port2_voltage;
    hal.port_t_voltage_mv = port_t_voltage;
    hal.reserve_voltage_mv = reserve_voltage;
    hal.power_source = power;
    hal.reserve_low = low;
    hal.measurement_validity = validity;
    hal.real_short_active = real_short;
    hal.identify_led = identify;
    hal.random_bytes = random_bytes;
    hal.settings_state = settings_state;
    hal.settings_salt = salt;
    hal.settings_write = settings;
    hal.verify_auth_proof = verify;
    hal.settings_name = get_name;
    hal.settings_set_name = set_name;
    hal.settings_set_password = set_password;
    hal.device_info = dev_info;
    hal.auth_lock_read = lock_read;
    hal.auth_lock_write = lock_write;
    hal.event_storage_init = storage_init;
    hal.event_storage_append = storage_append;
    hal.event_storage_read = storage_read;
    hal.tx_indicate = indicate;
    hal.tx_notify = notify;
    hal.diagnostic_error = diagnostic;
    hal.disconnect_after_setup = disconnect_after;
    hal.context = f;
    return hal;
}

static void hello(dpls_server_t *s, uint8_t *buf, uint32_t now) {
    uint8_t payload[16];
    size_t n;
    memset(payload, 1, 16);
    n = request(DPLS_MSG_HELLO, payload, 16, buf);
    assert(dpls_server_receive(s, buf, n, now));
}

static void auth(dpls_server_t *s, fake_t *f, uint8_t *buf) {
    dpls_hal_t hal = make_hal(f);
    dpls_frame_t resp;
    uint8_t payload[48];
    size_t n;
    dpls_server_init(s, &hal, 0);
    dpls_server_connected(s, 1000);
    hello(s, buf, 1100);
    memset(payload, 0, 48);
    payload[16] = 0xa5;
    n = request(DPLS_MSG_AUTH_PROOF, payload, 48, buf);
    dpls_server_receive(s, buf, n, 2100);
    assert(s->authenticated);
    assert(dpls_frame_decode(f->tx, f->tx_len, &resp));
    assert(resp.type == DPLS_MSG_AUTH_RESULT && resp.payload[0] == 0);
}

static uint8_t last_error(fake_t *f) {
    dpls_frame_t resp;
    assert(dpls_frame_decode(f->tx, f->tx_len, &resp));
    assert(resp.type == DPLS_MSG_ERROR);
    return resp.payload[0];
}

static uint8_t last_type(fake_t *f) {
    dpls_frame_t resp;
    assert(dpls_frame_decode(f->tx, f->tx_len, &resp));
    return resp.type;
}

static void test_bad_frame_and_unknown(void) {
    fake_t f = make_fake();
    dpls_hal_t hal = make_hal(&f);
    dpls_server_t s;
    uint8_t buf[DPLS_MAX_FRAME];
    uint8_t garbage[4] = {1, 2, 3, 4};

    dpls_server_init(&s, &hal, 0);
    dpls_server_connected(&s, 10);
    assert(!dpls_server_receive(&s, garbage, sizeof(garbage), 20));
    assert(last_error(&f) == 1);

    hello(&s, buf, 30);
    {
        uint8_t payload[16];
        size_t n;
        memset(payload, 0, 16);
        n = request(0x42, payload, 16, buf);
        assert(dpls_server_receive(&s, buf, n, 40));
        assert(last_error(&f) == 5);
    }
}

static void test_hello_guards(void) {
    fake_t f = make_fake();
    dpls_hal_t hal = make_hal(&f);
    dpls_server_t s;
    uint8_t buf[DPLS_MAX_FRAME];
    uint8_t payload[16];
    size_t n;

    dpls_server_init(&s, &hal, 0);
    memset(payload, 1, 16);
    n = request(DPLS_MSG_HELLO, payload, 16, buf);
    dpls_server_receive(&s, buf, n, 10);
    assert(last_error(&f) == 2); /* not connected */

    dpls_server_connected(&s, 20);
    f.encrypted = false;
    dpls_server_receive(&s, buf, n, 30);
    assert(last_error(&f) == 2);

    f.encrypted = true;
    n = request(DPLS_MSG_HELLO, payload, 15, buf);
    dpls_server_receive(&s, buf, n, 40);
    assert(last_error(&f) == 2);

    dpls_server_connected(&s, 50); /* already connected: no extra CONNECTED event */
    hello(&s, buf, 60);
    assert(last_type(&f) == DPLS_MSG_AUTH_CHALLENGE);

    dpls_server_disconnected(&s, 70);
    dpls_server_disconnected(&s, 80); /* idempotent */
}

static void test_rng_failure(void) {
    fake_t f = make_fake();
    dpls_server_t s;
    uint8_t buf[DPLS_MAX_FRAME];
    dpls_hal_t hal;
    f.random_ok = false;
    hal = make_hal(&f);
    dpls_server_init(&s, &hal, 0);
    dpls_server_connected(&s, 1000);
    hello(&s, buf, 1100);
    assert(s.critical_fault);
    assert(f.diagnostic_critical);
    assert(last_error(&f) == 5);
}

static void test_mode_paths(void) {
    fake_t f = make_fake();
    dpls_server_t s;
    uint8_t buf[DPLS_MAX_FRAME], payload[20];
    size_t n;
    dpls_frame_t resp;

    auth(&s, &f, buf);

    memcpy(payload, &s.session_id, 4);
    memcpy(payload + 4, s.session_token, 8);
    payload[12] = 1; payload[13] = payload[14] = payload[15] = 0;
    payload[16] = 9; /* invalid mode */
    n = request(DPLS_MSG_MODE_SET, payload, 17, buf);
    dpls_server_receive(&s, buf, n, 3000);
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.type == DPLS_MSG_COMMAND_RESULT && resp.payload[4] == 3);

    f.short_active = true;
    dpls_server_tick(&s, 3100);
    payload[12] = 2;
    payload[16] = DPLS_MODE_SHORT_1;
    n = request(DPLS_MSG_MODE_SET, payload, 17, buf);
    dpls_server_receive(&s, buf, n, 3200);
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.payload[4] == 5);

    f.short_active = false;
    dpls_server_tick(&s, 3300);
    f.apply_ok = false;
    payload[12] = 3;
    n = request(DPLS_MSG_MODE_SET, payload, 17, buf);
    dpls_server_receive(&s, buf, n, 3400);
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.payload[4] == 4);
    assert(s.mode == DPLS_MODE_NORMAL);

    f.apply_ok = true;
    payload[12] = 4;
    payload[16] = DPLS_MODE_OPEN_T;
    n = request(DPLS_MSG_MODE_SET, payload, 17, buf);
    dpls_server_receive(&s, buf, n, 3500);
    assert(s.mode == DPLS_MODE_OPEN_T);

    /* Cached command id is idempotent. */
    unsigned applies = f.apply_count;
    dpls_server_receive(&s, buf, n, 3600);
    assert(f.apply_count == applies);

    /* Low reserve forces Norma. */
    f.low_reserve = true;
    dpls_server_tick(&s, 3700);
    assert(s.mode == DPLS_MODE_NORMAL);

    payload[12] = 5;
    payload[16] = DPLS_MODE_SHORT_2;
    n = request(DPLS_MSG_MODE_SET, payload, 17, buf);
    dpls_server_receive(&s, buf, n, 3800);
    assert(s.mode == DPLS_MODE_SHORT_2);
    dpls_server_tick(&s, 3800u + DPLS_MODE_MAX_MS);
    assert(s.mode == DPLS_MODE_NORMAL);

    n = request(DPLS_MSG_MODE_SET, payload, 10, buf); /* too short */
    dpls_server_receive(&s, buf, n, 4000);
    assert(last_error(&f) == 2);
}

static void test_identify_keepalive_state_fallbacks(void) {
    fake_t f = make_fake();
    dpls_server_t s;
    uint8_t buf[DPLS_MAX_FRAME], payload[16];
    size_t n;
    dpls_frame_t resp;

    auth(&s, &f, buf);

    n = request(DPLS_MSG_IDENTIFY_START, NULL, 0, buf);
    dpls_server_receive(&s, buf, n, 3000);
    assert(s.identify_active && f.identify);
    dpls_server_receive(&s, buf, n, 3100); /* already active */
    assert(s.identify_active);

    n = request(DPLS_MSG_KEEP_ALIVE, NULL, 0, buf);
    dpls_server_receive(&s, buf, n, 3200);
    n = request(DPLS_MSG_IDENTIFY_STOP, NULL, 0, buf);
    dpls_server_receive(&s, buf, n, 3300);
    assert(!s.identify_active && !f.identify);
    dpls_server_receive(&s, buf, n, 3400); /* already stopped */

    memcpy(payload, &s.session_id, 4);
    memcpy(payload + 4, s.session_token, 8);
    n = request(DPLS_MSG_KEEP_ALIVE, payload, 12, buf);
    dpls_server_receive(&s, buf, n, 3500);

    payload[0] ^= 0xff; /* bad session */
    n = request(DPLS_MSG_KEEP_ALIVE, payload, 12, buf);
    dpls_server_receive(&s, buf, n, 3600);
    assert(last_error(&f) == 2);

    memcpy(payload, &s.session_id, 4);
    memcpy(payload + 4, s.session_token, 8);
    n = request(DPLS_MSG_STATE_GET, payload, 12, buf);
    dpls_server_receive(&s, buf, n, 3700);
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.type == DPLS_MSG_STATE_REPORT);
    assert(resp.payload_length == 25);

    /* Unencrypted keep-alive. */
    f.encrypted = false;
    n = request(DPLS_MSG_KEEP_ALIVE, NULL, 0, buf);
    dpls_server_receive(&s, buf, n, 3800);
    assert(last_error(&f) == 2);
    f.encrypted = true;

    n = request(DPLS_MSG_IDENTIFY_START, NULL, 0, buf);
    dpls_server_receive(&s, buf, n, 3900);
    dpls_server_disconnected(&s, 4000);
    assert(!s.identify_active);
    assert(s.mode == DPLS_MODE_NORMAL);

    f.encrypted = false;
    dpls_server_connected(&s, 4100);
    n = request(DPLS_MSG_IDENTIFY_START, NULL, 0, buf);
    dpls_server_receive(&s, buf, n, 4200);
    assert(last_error(&f) == 2);
}

static void test_settings_failures_and_setup(void) {
    fake_t f = make_fake();
    dpls_server_t s;
    uint8_t buf[DPLS_MAX_FRAME], payload[80];
    size_t n;
    dpls_frame_t resp;

    auth(&s, &f, buf);

    memcpy(payload, &s.session_id, 4);
    memcpy(payload + 4, s.session_token, 8);
    payload[12] = 1; payload[13] = payload[14] = payload[15] = 0;
    payload[16] = 0; /* empty name */
    n = request(DPLS_MSG_NAME_SET, payload, 17, buf);
    dpls_server_receive(&s, buf, n, 3000);
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.type == DPLS_MSG_SETTINGS_RESULT && resp.payload[4] == 1);

    payload[16] = 3; memcpy(payload + 17, "abc", 3);
    f.set_name_ok = false;
    n = request(DPLS_MSG_NAME_SET, payload, 20, buf);
    dpls_server_receive(&s, buf, n, 3100);
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.payload[4] == 2);

    f.set_password_ok = false;
    memcpy(payload, &s.session_id, 4);
    memcpy(payload + 4, s.session_token, 8);
    payload[12] = 2; payload[13] = payload[14] = payload[15] = 0;
    memset(payload + 16, 1, 48);
    n = request(DPLS_MSG_PASSWORD_SET, payload, 64, buf);
    dpls_server_receive(&s, buf, n, 3200);
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.payload[4] == 2);
    assert(s.authenticated);

    n = request(DPLS_MSG_NAME_SET, payload, 10, buf);
    dpls_server_receive(&s, buf, n, 3300);
    assert(last_error(&f) == 2);

    /* SETUP on an already-commissioned device. */
    memcpy(payload, &s.session_id, 4);
    payload[4] = 2; payload[5] = 'A'; payload[6] = 'B';
    memset(payload + 7, 0x22, 48);
    n = request(DPLS_MSG_SETUP, payload, 55, buf);
    dpls_server_receive(&s, buf, n, 3400);
    assert(last_error(&f) == 2);

    /* Fresh empty device: bad name length, then write failure, then success. */
    {
        fake_t f2 = make_fake();
        dpls_server_t s2;
        f2.initialized = false;
        dpls_hal_t hal2 = make_hal(&f2);
        dpls_server_init(&s2, &hal2, 0);
        dpls_server_connected(&s2, 1000);
        hello(&s2, buf, 1100);
        memcpy(payload, &s2.session_id, 4);
        payload[4] = 0;
        n = request(DPLS_MSG_SETUP, payload, 54, buf);
        dpls_server_receive(&s2, buf, n, 1200);
        assert(last_error(&f2) == 3);

        payload[4] = 2; payload[5] = 'A'; payload[6] = 'B';
        memset(payload + 7, 0x22, 48);
        f2.settings_write_ok = false;
        n = request(DPLS_MSG_SETUP, payload, 55, buf);
        dpls_server_receive(&s2, buf, n, 1300);
        assert(last_error(&f2) == 4);

        f2.settings_write_ok = true;
        n = request(DPLS_MSG_SETUP, payload, 55, buf);
        dpls_server_receive(&s2, buf, n, 1400);
        assert(f2.initialized);
        assert(dpls_frame_decode(f2.tx, f2.tx_len, &resp));
        assert(resp.type == DPLS_MSG_AUTH_RESULT && resp.payload[0] == 3);
        dpls_server_tick(&s2, 1400 + 500);
        assert(f2.disconnect_count == 1);
    }
}

static void test_storage_and_log_errors(void) {
    fake_t f = make_fake();
    dpls_server_t s;
    uint8_t buf[DPLS_MAX_FRAME], payload[16];
    size_t n;
    dpls_frame_t resp;

    f.storage_init_ok = false;
    {
        dpls_hal_t hal = make_hal(&f);
        dpls_server_init(&s, &hal, 0);
        assert(f.diagnostic_count >= 1);
        assert(!f.diagnostic_critical);
    }

    f = make_fake();
    f.storage_append_ok = false;
    auth(&s, &f, buf);
    assert(f.diagnostic_count >= 1);

    f = make_fake();
    auth(&s, &f, buf);
    memcpy(payload, &s.session_id, 4);
    memcpy(payload + 4, s.session_token, 8);
    payload[12] = payload[13] = 0;
    n = request(DPLS_MSG_LOG_START, payload, 14, buf);
    dpls_server_receive(&s, buf, n, 3000);
    assert(last_type(&f) == DPLS_MSG_LOG_INFO);

    f.storage_read_ok = false;
    n = request(DPLS_MSG_LOG_ACK, payload, 14, buf);
    dpls_server_receive(&s, buf, n, 3100);
    assert(last_error(&f) == 6);

    f.storage_read_ok = true;
    n = request(DPLS_MSG_LOG_START, payload, 14, buf);
    dpls_server_receive(&s, buf, n, 3200);
    payload[12] = 0xff; payload[13] = 0xff; /* past the end */
    n = request(DPLS_MSG_LOG_ACK, payload, 14, buf);
    dpls_server_receive(&s, buf, n, 3300);
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.type == DPLS_MSG_LOG_RESULT);

    n = request(DPLS_MSG_LOG_START, payload, 8, buf);
    dpls_server_receive(&s, buf, n, 3400);
    assert(last_error(&f) == 2);

    /* event_count clamp when storage reports more than capacity */
    f = make_fake();
    f.stored_count = DPLS_EVENT_CAPACITY + 10u;
    f.stored_next = DPLS_EVENT_CAPACITY + 11u;
    {
        dpls_hal_t hal = make_hal(&f);
        dpls_server_init(&s, &hal, 0);
        assert(s.event_count == DPLS_EVENT_CAPACITY);
    }
}

static void test_lock_write_failure_and_null_hal(void) {
    fake_t f = make_fake();
    dpls_server_t s;
    uint8_t buf[DPLS_MAX_FRAME], payload[48];
    size_t n;

    f.lock_write_ok = false;
    {
        dpls_hal_t hal = make_hal(&f);
        dpls_server_init(&s, &hal, 0);
        dpls_server_connected(&s, 1000);
        hello(&s, buf, 1100);
        memset(payload, 0, 48);
        payload[16] = 0;
        n = request(DPLS_MSG_AUTH_PROOF, payload, 48, buf);
        dpls_server_receive(&s, buf, n, 2000);
        dpls_server_receive(&s, buf, n, 3000);
        dpls_server_receive(&s, buf, n, 4000);
        dpls_server_receive(&s, buf, n, 5000);
        dpls_server_receive(&s, buf, n, 6000);
        assert(s.blocked_until_ms != 0);
        assert(f.diagnostic_count >= 1);
    }

    /* STATE_GET with missing optional voltage callbacks. */
    f = make_fake();
    {
        dpls_hal_t hal = make_hal(&f);
        dpls_frame_t resp;
        hal.voltage_mv = NULL;
        hal.port1_voltage_mv = NULL;
        hal.port2_voltage_mv = NULL;
        hal.port_t_voltage_mv = NULL;
        hal.reserve_voltage_mv = NULL;
        hal.measurement_validity = NULL;
        hal.real_short_active = NULL;
        dpls_server_init(&s, &hal, 0);
        dpls_server_connected(&s, 1000);
        hello(&s, buf, 1100);
        memset(payload, 0, 48);
        payload[16] = 0xa5;
        n = request(DPLS_MSG_AUTH_PROOF, payload, 48, buf);
        dpls_server_receive(&s, buf, n, 2100);
        memcpy(payload, &s.session_id, 4);
        memcpy(payload + 4, s.session_token, 8);
        n = request(DPLS_MSG_STATE_GET, payload, 12, buf);
        dpls_server_receive(&s, buf, n, 2200);
        assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
        assert(resp.type == DPLS_MSG_STATE_REPORT);
        assert(resp.payload[16] == 0);
        assert(resp.payload[17] == 0 && resp.payload[19] == 0);
        dpls_server_tick(&s, 2300); /* real_short_active NULL */
    }
}

static void test_auth_proof_guards(void) {
    fake_t f = make_fake();
    dpls_hal_t hal = make_hal(&f);
    dpls_server_t s;
    uint8_t buf[DPLS_MAX_FRAME], payload[48];
    size_t n;

    dpls_server_init(&s, &hal, 0);
    dpls_server_connected(&s, 1000);
    memset(payload, 0, 48);
    payload[16] = 0xa5;
    n = request(DPLS_MSG_AUTH_PROOF, payload, 48, buf);
    dpls_server_receive(&s, buf, n, 1100);
    assert(last_error(&f) == 2); /* no hello yet */

    hello(&s, buf, 1200);
    n = request(DPLS_MSG_AUTH_PROOF, payload, 47, buf);
    dpls_server_receive(&s, buf, n, 1300);
    assert(last_error(&f) == 2);

    n = request(DPLS_MSG_DEVICE_INFO_GET, payload, 4, buf);
    dpls_server_receive(&s, buf, n, 1400);
    assert(last_error(&f) == 2);

    n = request(DPLS_MSG_STATE_GET, payload, 4, buf);
    dpls_server_receive(&s, buf, n, 1500);
    assert(last_error(&f) == 2);

    n = request(DPLS_MSG_LOG_ACK, payload, 4, buf);
    dpls_server_receive(&s, buf, n, 1600);
    assert(last_error(&f) == 2);

    n = request(DPLS_MSG_PASSWORD_SET, payload, 12, buf);
    dpls_server_receive(&s, buf, n, 1700);
    assert(last_error(&f) == 2);
}

int main(void) {
    test_bad_frame_and_unknown();
    test_hello_guards();
    test_rng_failure();
    test_mode_paths();
    test_identify_keepalive_state_fallbacks();
    test_settings_failures_and_setup();
    test_storage_and_log_errors();
    test_lock_write_failure_and_null_hal();
    test_auth_proof_guards();
    puts("test_server_edges: OK");
    return 0;
}
