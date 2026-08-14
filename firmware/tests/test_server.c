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
    char name[32];
    uint8_t last_salt[16];
    uint8_t last_verifier[32];
    bool password_set;
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
static uint16_t port1_voltage(void *c) { (void)c; return 24100; }
static uint16_t port2_voltage(void *c) { (void)c; return 23800; }
static uint16_t port_t_voltage(void *c) { (void)c; return 23700; }
static uint16_t reserve_voltage(void *c) { (void)c; return 4200; }
static dpls_power_t power(void *c) { return ((fake_t *)c)->power_src; }
static bool low(void *c) { return ((fake_t *)c)->low_reserve; }
static uint8_t validity(void *c) { return ((fake_t *)c)->validity; }
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
static bool lock_read(void *c) { return ((fake_t *)c)->auth_locked; }
static bool lock_write(void *c, bool locked) { ((fake_t *)c)->auth_locked = locked; return true; }
static void get_name(void *c, char out[DPLS_NAME_MAX + 1u]) {
    fake_t *f = c; memcpy(out, f->name, DPLS_NAME_MAX); out[DPLS_NAME_MAX] = '\0';
}
static bool set_name(void *c, const char *n) {
    fake_t *f = c; size_t i = 0; while (i < 31u && n[i]) { f->name[i] = n[i]; ++i; } f->name[i] = '\0'; return true;
}
static bool set_password(void *c, const uint8_t s[16], const uint8_t v[32]) {
    fake_t *f = c; memcpy(f->last_salt, s, 16); memcpy(f->last_verifier, v, 32); f->password_set = true; return true;
}
static void dev_info(void *c, dpls_device_info_t *out) {
    (void)c; out->device_id = 0x0a0b0c0du;
    out->fw_major = DPLS_FW_VERSION_MAJOR;
    out->fw_minor = DPLS_FW_VERSION_MINOR;
    out->fw_patch = DPLS_FW_VERSION_PATCH; out->hw_revision = 1; out->capabilities = DPLS_CAP_ADC_PRESENT;
}

static size_t request(uint8_t type, const uint8_t *p, uint16_t n, uint8_t *out) {
    dpls_frame_t f; memset(&f, 0, sizeof(f)); f.type = type; f.sequence = 1; f.payload_length = n; if (n) memcpy(f.payload, p, n);
    return dpls_frame_encode(&f, out, DPLS_MAX_FRAME);
}

static void do_hello(dpls_server_t *s, uint8_t *buf, uint32_t now);
static void send_proof(dpls_server_t *s, uint8_t *buf, bool correct, uint32_t now);

static dpls_hal_t lockout_hal(fake_t *f) {
    dpls_hal_t hal = {
        .link_encrypted = encrypted, .hardware_apply_mode = apply, .hardware_safe_normal = normal,
        .voltage_mv = voltage, .port1_voltage_mv = port1_voltage,
        .port2_voltage_mv = port2_voltage, .port_t_voltage_mv = port_t_voltage,
        .reserve_voltage_mv = reserve_voltage, .power_source = power, .reserve_low = low,
        .measurement_validity = validity, .real_short_active = real_short, .identify_led = identify,
        .random_bytes = random_bytes, .settings_state = settings_state, .settings_salt = salt,
        .settings_write = settings, .verify_auth_proof = verify,
        .settings_name = get_name, .settings_set_name = set_name,
        .settings_set_password = set_password, .device_info = dev_info,
        .auth_lock_read = lock_read, .auth_lock_write = lock_write,
        .event_storage_init = storage_init, .event_storage_append = storage_append,
        .event_storage_read = storage_read,
        .tx_indicate = indicate, .tx_notify = notify, .context = f,
    };
    return hal;
}

/* Authenticate a fresh server against the lockout_hal fake and leave it ready
 * for post-auth requests. Shared by the settings tests. */
static void authenticate(dpls_server_t *s, fake_t *f, uint8_t *buf) {
    dpls_hal_t hal = lockout_hal(f);
    dpls_server_init(s, &hal, 0);
    dpls_server_connected(s, 1000); do_hello(s, buf, 1100);
    send_proof(s, buf, true, 2000);
    assert(s->authenticated);
}

/* ТЗ 7.4.1f: change name / password over the authenticated session, and read
 * back device info instead of hard-coded UI strings. */
static void test_device_settings(void) {
    uint8_t buf[DPLS_MAX_FRAME], payload[80]; size_t n; dpls_frame_t resp;
    fake_t f = {.encrypted = true, .initialized = true};
    dpls_server_t s;
    memcpy(f.name, "Test-DPLS-old", 14);

    authenticate(&s, &f, buf);

    /* DEVICE_INFO_GET → DEVICE_INFO_REPORT with id/versions/caps/name. */
    memcpy(payload, &s.session_id, 4); memcpy(payload + 4, s.session_token, 8);
    n = request(DPLS_MSG_DEVICE_INFO_GET, payload, 12, buf); dpls_server_receive(&s, buf, n, 2500);
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.type == DPLS_MSG_DEVICE_INFO_REPORT);
    assert(resp.payload[0] == 0x0d && resp.payload[3] == 0x0a);      /* device_id LE */
    assert(resp.payload[4] == DPLS_PROTOCOL_VERSION);
    assert(resp.payload[5] == DPLS_FW_VERSION_MAJOR &&
           resp.payload[6] == DPLS_FW_VERSION_MINOR);
    assert(resp.payload[9] == DPLS_CAP_ADC_PRESENT);
    assert(resp.payload[11] == 13);                                  /* name length */
    assert(memcmp(resp.payload + 12, "Test-DPLS-old", 13) == 0);

    /* NAME_SET → a single SETTINGS_RESULT (no back-to-back DEVICE_INFO_REPORT). */
    memcpy(payload, &s.session_id, 4); memcpy(payload + 4, s.session_token, 8);
    payload[12] = 0x77; payload[13] = payload[14] = payload[15] = 0; /* command_id */
    payload[16] = 6; memcpy(payload + 17, "NewNam", 6);
    n = request(DPLS_MSG_NAME_SET, payload, 23, buf); dpls_server_receive(&s, buf, n, 3000);
    assert(memcmp(f.name, "NewNam", 6) == 0 && f.name[6] == '\0');
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.type == DPLS_MSG_SETTINGS_RESULT && resp.payload[0] == 0x77 && resp.payload[4] == 0);
    /* The client re-pulls DEVICE_INFO to see the new name. */
    memcpy(payload, &s.session_id, 4); memcpy(payload + 4, s.session_token, 8);
    n = request(DPLS_MSG_DEVICE_INFO_GET, payload, 12, buf); dpls_server_receive(&s, buf, n, 3100);
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.type == DPLS_MSG_DEVICE_INFO_REPORT && resp.payload[11] == 6);
    assert(memcmp(resp.payload + 12, "NewNam", 6) == 0);

    /* PASSWORD_SET → SETTINGS_RESULT ok, session invalidated, disconnect armed. */
    memcpy(payload, &s.session_id, 4); memcpy(payload + 4, s.session_token, 8);
    payload[12] = 0x88; payload[13] = payload[14] = payload[15] = 0;
    memset(payload + 16, 0xcc, 16);  /* new salt */
    memset(payload + 32, 0xdd, 32);  /* new verifier */
    n = request(DPLS_MSG_PASSWORD_SET, payload, 64, buf); dpls_server_receive(&s, buf, n, 4000);
    assert(f.password_set);
    assert(f.last_salt[0] == 0xcc && f.last_verifier[0] == 0xdd);
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.type == DPLS_MSG_SETTINGS_RESULT && resp.payload[0] == 0x88 && resp.payload[4] == 0);
    assert(!s.authenticated);                                        /* session dropped */
    assert(s.setup_disconnect_deadline_ms != 0);                     /* disconnect scheduled */

    /* The invalidated session no longer authorises commands. */
    memcpy(payload, &s.session_id, 4); memcpy(payload + 4, s.session_token, 8);
    n = request(DPLS_MSG_STATE_GET, payload, 12, buf); dpls_server_receive(&s, buf, n, 4100);
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.type == DPLS_MSG_ERROR);
}

static void do_hello(dpls_server_t *s, uint8_t *buf, uint32_t now) {
    uint8_t payload[16]; size_t n; memset(payload, 1, 16);
    n = request(DPLS_MSG_HELLO, payload, 16, buf); dpls_server_receive(s, buf, n, now);
}

/* proof[0] (== payload[16]) drives the fake verify: 0xa5 is the right password. */
static void send_proof(dpls_server_t *s, uint8_t *buf, bool correct, uint32_t now) {
    uint8_t payload[48]; size_t n; memset(payload, 0, 48);
    payload[16] = correct ? 0xa5u : 0x00u;
    n = request(DPLS_MSG_AUTH_PROOF, payload, 48, buf); dpls_server_receive(s, buf, n, now);
}

/* ТЗ 7.3.5 brute-force protection: the failed-attempt count and the lock must
 * survive a reconnect and a reboot, and only clear on success/expiry/reset. */
static void test_auth_lockout(void) {
    uint8_t buf[DPLS_MAX_FRAME];
    fake_t f = {.encrypted = true, .initialized = true};
    dpls_hal_t hal = lockout_hal(&f);
    dpls_server_t s;
    uint32_t block_until;

    dpls_server_init(&s, &hal, 0);
    dpls_server_connected(&s, 1000); do_hello(&s, buf, 1100);
    /* Four wrong attempts, paced >1 s apart so none is rate-limited. */
    send_proof(&s, buf, false, 2000); assert(s.failed_auth_attempts == 1); assert(!s.blocked_until_ms);
    send_proof(&s, buf, false, 3000); assert(s.failed_auth_attempts == 2);
    send_proof(&s, buf, false, 4000); assert(s.failed_auth_attempts == 3);
    send_proof(&s, buf, false, 5000); assert(s.failed_auth_attempts == 4); assert(!s.blocked_until_ms);

    /* A too-fast fifth attempt is rejected without counting. */
    send_proof(&s, buf, false, 5200); assert(s.failed_auth_attempts == 4);

    /* Reconnect must NOT reset the counter. */
    dpls_server_disconnected(&s, 5500);
    dpls_server_connected(&s, 6000); do_hello(&s, buf, 6100);
    assert(s.failed_auth_attempts == 4);

    /* Fifth wrong attempt blocks and persists the marker. */
    send_proof(&s, buf, false, 7000);
    assert(s.failed_auth_attempts == 5);
    assert(s.blocked_until_ms != 0);
    assert(f.auth_locked);
    block_until = s.blocked_until_ms;

    /* Attempts during the block are refused (status 2) and stay blocked. */
    send_proof(&s, buf, true, 8000); assert(!s.authenticated); assert(s.blocked_until_ms == block_until);

    /* Reboot while locked re-arms a fresh full block from the new boot time. */
    {
        dpls_server_t s2;
        dpls_server_init(&s2, &hal, 100000);
        assert(f.auth_locked);
        assert(s2.failed_auth_attempts == DPLS_AUTH_MAX_ATTEMPTS);
        assert(s2.blocked_until_ms == 100000u + DPLS_AUTH_BLOCK_MS);
        dpls_server_connected(&s2, 100100); do_hello(&s2, buf, 100200);
        /* Still blocked right after reboot. */
        send_proof(&s2, buf, true, 101000); assert(!s2.authenticated);
        /* After the block expires a correct password authenticates and clears
         * both the counter and the persisted marker. */
        do_hello(&s2, buf, s2.blocked_until_ms + 1000u);
        send_proof(&s2, buf, true, s2.blocked_until_ms + 2000u);
        assert(s2.authenticated);
        assert(s2.failed_auth_attempts == 0);
        assert(!f.auth_locked);
    }
}

/* SETUP is only accepted inside the commissioning window after boot. */
static void test_setup_window(void) {
    uint8_t buf[DPLS_MAX_FRAME], payload[64]; size_t n; dpls_frame_t resp;
    fake_t f = {.encrypted = true, .initialized = false};
    dpls_hal_t hal = lockout_hal(&f);
    dpls_server_t s;
    uint32_t late;

    dpls_server_init(&s, &hal, 0);
    dpls_server_connected(&s, 1000); do_hello(&s, buf, 1100);
    /* Build a minimal valid SETUP frame (name "AB" + 16 salt + 32 verifier). */
    memcpy(payload, &s.session_id, 4); payload[4] = 2; payload[5] = 'A'; payload[6] = 'B';
    memset(payload + 7, 0x22, 16); memset(payload + 23, 0x33, 32);

    /* Just past the window: refused with the dedicated code 7, still EMPTY. */
    late = s.boot_ms + DPLS_SETUP_WINDOW_MS + 1000u;
    n = request(DPLS_MSG_SETUP, payload, 55, buf); dpls_server_receive(&s, buf, n, late);
    assert(dpls_frame_decode(f.tx, f.tx_len, &resp));
    assert(resp.type == DPLS_MSG_ERROR && resp.payload[0] == 7);
    assert(!f.initialized);

    /* Inside the window (fresh boot) the same frame commissions the device. */
    {
        fake_t f2 = {.encrypted = true, .initialized = false};
        dpls_hal_t hal2 = lockout_hal(&f2);
        dpls_server_t s2;
        dpls_server_init(&s2, &hal2, 0);
        dpls_server_connected(&s2, 1000); do_hello(&s2, buf, 1100);
        memcpy(payload, &s2.session_id, 4);
        n = request(DPLS_MSG_SETUP, payload, 55, buf); dpls_server_receive(&s2, buf, n, 2000);
        assert(f2.initialized);
    }
}

int main(void) {
    fake_t fake = {.encrypted = true, .initialized = true};
    dpls_hal_t hal = {
        .link_encrypted = encrypted, .hardware_apply_mode = apply, .hardware_safe_normal = normal,
        .voltage_mv = voltage, .port1_voltage_mv = port1_voltage,
        .port2_voltage_mv = port2_voltage, .port_t_voltage_mv = port_t_voltage,
        .reserve_voltage_mv = reserve_voltage, .power_source = power, .reserve_low = low,
        .measurement_validity = validity,
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
    /* STATE_REPORT keeps its legacy prefix and appends +1, +2, +T and reserve. */
    fake.validity = DPLS_STATE_PORT_1_VALID | DPLS_STATE_PORT_2_VALID |
                    DPLS_STATE_PORT_T_VALID | DPLS_STATE_RESERVE_VOLTAGE_VALID |
                    DPLS_STATE_POWER_VALID;
    memcpy(payload, &server.session_id, 4); memcpy(payload + 4, server.session_token, 8);
    n = request(DPLS_MSG_STATE_GET, payload, 12, buf); dpls_server_receive(&server, buf, n, 35);
    assert(dpls_frame_decode(fake.tx, fake.tx_len, &response));
    assert(response.type == DPLS_MSG_STATE_REPORT);
    assert(response.payload_length == 25);
    assert(response.payload[2] == (uint8_t)(24100u & 0xffu) && response.payload[3] == (uint8_t)(24100u >> 8));
    assert(response.payload[16] == fake.validity);
    assert(response.payload[17] == (uint8_t)(24100u & 0xffu) && response.payload[18] == (uint8_t)(24100u >> 8));
    assert(response.payload[19] == (uint8_t)(23800u & 0xffu) && response.payload[20] == (uint8_t)(23800u >> 8));
    assert(response.payload[21] == (uint8_t)(23700u & 0xffu) && response.payload[22] == (uint8_t)(23700u >> 8));
    assert(response.payload[23] == (uint8_t)(4200u & 0xffu) && response.payload[24] == (uint8_t)(4200u >> 8));
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

    test_auth_lockout();
    test_setup_window();
    test_device_settings();

    puts("test_dpls_server: OK"); return 0;
}
