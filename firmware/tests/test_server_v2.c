#include "dpls_server.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    bool encrypted;
    bool initialized;
    bool normal;
    bool short_active;
    bool low_reserve;
    bool identify_active;
    bool reject_proof;
    bool fail_random;
    bool fail_apply;
    bool fail_settings_write;
    bool fail_set_name;
    bool fail_set_password;
    bool fail_storage_read;
    bool auth_locked;
    unsigned apply_count;
    unsigned disconnect_count;
    unsigned diagnostic_count;
    unsigned power_read_count;
    unsigned reserve_read_count;
    unsigned short_read_count;
    uint8_t tx[DPLS_MAX_FRAME];
    size_t tx_len;
    char name[32];
    dpls_event_t events[DPLS_EVENT_CAPACITY];
    uint16_t event_count;
    uint32_t next_sequence;
} fake_t;

static bool encrypted(void *c) { return ((fake_t *)c)->encrypted; }
static bool apply(void *c, dpls_mode_t mode) {
    fake_t *f = c;
    ++f->apply_count;
    if (f->fail_apply) return false;
    f->normal = mode == DPLS_MODE_NORMAL;
    return true;
}
static void normal(void *c) { ((fake_t *)c)->normal = true; }
static uint16_t voltage(void *c) { (void)c; return 24000u; }
static dpls_power_t power(void *c) {
    ++((fake_t *)c)->power_read_count;
    return DPLS_POWER_LINE;
}
static bool reserve_low(void *c) {
    ++((fake_t *)c)->reserve_read_count;
    return ((fake_t *)c)->low_reserve;
}
static uint8_t validity(void *c) { (void)c; return DPLS_STATE_LINE_VOLTAGE_VALID | DPLS_STATE_POWER_VALID; }
static void identify(void *c, bool enabled) { ((fake_t *)c)->identify_active = enabled; }
static bool random_bytes(void *c, uint8_t *out, size_t length) {
    size_t i;
    if (((fake_t *)c)->fail_random) return false;
    for (i = 0; i < length; ++i) out[i] = (uint8_t)(i + 1u);
    return true;
}
static bool real_short(void *c) {
    ++((fake_t *)c)->short_read_count;
    return ((fake_t *)c)->short_active;
}
static dpls_settings_state_t settings_state(void *c) {
    return ((fake_t *)c)->initialized ? DPLS_SETTINGS_VALID : DPLS_SETTINGS_EMPTY;
}
static void salt(void *c, uint8_t out[16]) { (void)c; memset(out, 0x22, 16); }
static bool settings_write(void *c, const char *name, const uint8_t salt_bytes[16], const uint8_t verifier[32]) {
    fake_t *f = c;
    (void)salt_bytes;
    (void)verifier;
    if (f->fail_settings_write) return false;
    f->initialized = true;
    strncpy(f->name, name, sizeof(f->name) - 1u);
    return true;
}
static void settings_name(void *c, char out[DPLS_NAME_MAX + 1u]) {
    fake_t *f = c;
    strncpy(out, f->name[0] ? f->name : "Test-DPLS", DPLS_NAME_MAX);
    out[DPLS_NAME_MAX] = '\0';
}
static bool settings_set_name(void *c, const char *name) {
    fake_t *f = c;
    if (f->fail_set_name) return false;
    strncpy(f->name, name, sizeof(f->name) - 1u);
    return true;
}
static bool settings_set_password(void *c, const uint8_t salt_bytes[16], const uint8_t verifier[32]) {
    (void)salt_bytes;
    (void)verifier;
    return !((fake_t *)c)->fail_set_password;
}
static void device_info(void *c, dpls_device_info_t *out) {
    (void)c;
    memset(out, 0, sizeof(*out));
    out->device_id = 0x1234u;
    out->fw_major = DPLS_FW_VERSION_MAJOR;
    out->fw_minor = DPLS_FW_VERSION_MINOR;
    out->fw_patch = DPLS_FW_VERSION_PATCH;
}
static bool verify(void *c, const uint8_t device_nonce[16], const uint8_t client_nonce[16],
                   uint32_t session_id, const uint8_t proof[32]) {
    (void)device_nonce; (void)client_nonce; (void)session_id; (void)proof;
    return !((fake_t *)c)->reject_proof;
}
static bool storage_init(void *c, uint16_t *count, uint32_t *next_sequence) {
    fake_t *f = c;
    if (f->fail_storage_read) return false;
    *count = f->event_count;
    *next_sequence = f->next_sequence ? f->next_sequence : 1u;
    return true;
}

static bool lock_read(void *c) { return ((fake_t *)c)->auth_locked; }
static bool lock_write(void *c, bool locked) {
    ((fake_t *)c)->auth_locked = locked;
    return true;
}
static void disconnect(void *c) { ++((fake_t *)c)->disconnect_count; }
static void diagnostic(void *c, bool critical) {
    (void)critical;
    ++((fake_t *)c)->diagnostic_count;
}
static bool storage_append(void *c, const dpls_event_t *event) {
    fake_t *f = c;
    if (f->event_count == DPLS_EVENT_CAPACITY) {
        memmove(f->events, f->events + 1, (DPLS_EVENT_CAPACITY - 1u) * sizeof(*f->events));
        --f->event_count;
    }
    f->events[f->event_count++] = *event;
    f->next_sequence = event->sequence + 1u;
    return true;
}
static bool storage_read(void *c, uint32_t sequence, dpls_event_t *out) {
    fake_t *f = c;
    uint16_t i;
    for (i = 0; i < f->event_count; ++i) {
        if (f->events[i].sequence == sequence) { *out = f->events[i]; return true; }
    }
    return false;
}
static bool tx(void *c, const uint8_t *frame, size_t length) {
    fake_t *f = c;
    assert(length <= sizeof(f->tx));
    memcpy(f->tx, frame, length);
    f->tx_len = length;
    return true;
}

static dpls_hal_t hal(fake_t *f) {
    dpls_hal_t h;
    memset(&h, 0, sizeof(h));
    h.link.encrypted = encrypted;
    h.link.indicate = tx;
    h.link.disconnect = disconnect;
    h.hardware.apply_mode = apply;
    h.hardware.safe_normal = normal;
    h.hardware.voltage_mv = voltage;
    h.hardware.port1_voltage_mv = voltage;
    h.hardware.power_source = power;
    h.hardware.reserve_low = reserve_low;
    h.hardware.measurement_validity = validity;
    h.hardware.identify_led = identify;
    h.hardware.real_short_active = real_short;
    h.hardware.device_info = device_info;
    h.settings.state = settings_state;
    h.settings.salt = salt;
    h.settings.write = settings_write;
    h.settings.name = settings_name;
    h.settings.set_name = settings_set_name;
    h.settings.set_password = settings_set_password;
    h.auth.random_bytes = random_bytes;
    h.auth.verify_proof = verify;
    h.auth.lock_read = lock_read;
    h.auth.lock_write = lock_write;
    h.events.init = storage_init;
    h.events.append = storage_append;
    h.events.read = storage_read;
    h.context = f;
    h.diagnostic_error = diagnostic;
    return h;
}

static size_t request(uint8_t type, uint16_t sequence, const uint8_t *payload, uint16_t length, uint8_t *out) {
    dpls_frame_t f;
    memset(&f, 0, sizeof(f));
    f.type = type;
    f.flags = DPLS_FLAG_REQUEST;
    f.sequence = sequence;
    f.payload_length = length;
    if (length) memcpy(f.payload, payload, length);
    return dpls_frame_encode(&f, out, DPLS_MAX_FRAME);
}

static dpls_frame_t response(fake_t *f) {
    dpls_frame_t frame;
    assert(dpls_frame_decode(f->tx, f->tx_len, &frame));
    assert((frame.flags & DPLS_FLAG_RESPONSE) != 0u);
    return frame;
}

static void authenticate(dpls_server_t *server, fake_t *fake, uint8_t *buf) {
    uint8_t hello[16] = {0};
    uint8_t proof[48] = {0};
    size_t n;
    dpls_frame_t r;

    dpls_server_connected(server, 1u);
    n = request(DPLS_MSG_HELLO, 10u, hello, sizeof(hello), buf);
    assert(dpls_server_receive(server, buf, n, 2u));
    r = response(fake);
    assert(r.type == DPLS_MSG_AUTH_CHALLENGE && r.sequence == 10u);

    n = request(DPLS_MSG_AUTH_PROOF, 11u, proof, sizeof(proof), buf);
    assert(dpls_server_receive(server, buf, n, 3u));
    r = response(fake);
    assert(r.type == DPLS_MSG_AUTH_RESULT && r.sequence == 11u && r.payload[0] == 0u);
    assert(dpls_server_authenticated(server));
}

static void auth_payload(const dpls_server_t *s, uint8_t out[12]) {
    out[0] = (uint8_t)s->session.session_id;
    out[1] = (uint8_t)(s->session.session_id >> 8);
    out[2] = (uint8_t)(s->session.session_id >> 16);
    out[3] = (uint8_t)(s->session.session_id >> 24);
    memcpy(out + 4, s->session.token, DPLS_SESSION_TOKEN_SIZE);
}

static void init_server(fake_t *fake, dpls_server_t *server, bool initialized) {
    dpls_hal_t h;
    memset(fake, 0, sizeof(*fake));
    fake->encrypted = true;
    fake->initialized = initialized;
    fake->next_sequence = 1u;
    h = hal(fake);
    dpls_server_init(server, &h, 0u);
}

static uint8_t response_status(fake_t *fake, uint8_t type) {
    dpls_frame_t frame = response(fake);
    assert(frame.type == type && frame.payload_length != 0u);
    return frame.payload[0];
}

static void test_core_flow(void) {
    fake_t fake;
    dpls_server_t server;
    dpls_frame_t r;
    uint8_t buf[DPLS_MAX_FRAME];
    uint8_t payload[64];
    size_t n;
    unsigned apply_count;

    init_server(&fake, &server, true);
    assert(server.safety.mode == DPLS_MODE_NORMAL && fake.normal);
    authenticate(&server, &fake, buf);

    auth_payload(&server, payload);
    n = request(DPLS_MSG_STATE_GET, 20u, payload, 12u, buf);
    assert(dpls_server_receive(&server, buf, n, 4u));
    r = response(&fake);
    assert(r.type == DPLS_MSG_STATE_REPORT && r.sequence == 20u && r.payload[0] == DPLS_MODE_NORMAL);

    auth_payload(&server, payload);
    payload[12] = DPLS_MODE_SHORT_1;
    n = request(DPLS_MSG_MODE_SET, 21u, payload, 13u, buf);
    assert(dpls_server_receive(&server, buf, n, 5u));
    r = response(&fake);
    assert(r.type == DPLS_MSG_COMMAND_RESULT && r.sequence == 21u && r.payload_length == 4u);
    assert(server.safety.mode == DPLS_MODE_SHORT_1);
    apply_count = fake.apply_count;
    assert(dpls_server_receive(&server, buf, n, 6u));
    assert(fake.apply_count == apply_count); /* duplicate sequence is idempotent */

    dpls_server_tick(&server, 5u + DPLS_MODE_MAX_MS);
    assert(server.safety.mode == DPLS_MODE_NORMAL && fake.normal);

    auth_payload(&server, payload);
    payload[12] = DPLS_MODE_SHORT_2;
    n = request(DPLS_MSG_MODE_SET, 22u, payload, 13u, buf);
    assert(dpls_server_receive(&server, buf, n, 7u));
    fake.short_active = true;
    dpls_server_tick(&server, 8u);
    assert(server.safety.mode == DPLS_MODE_NORMAL);
    fake.short_active = false;

    auth_payload(&server, payload);
    payload[12] = 0x80; payload[13] = 0x77; payload[14] = 0x7c; payload[15] = 0x6a;
    n = request(DPLS_MSG_TIME_SYNC, 30u, payload, 16u, buf);
    assert(dpls_server_receive(&server, buf, n, 9u));
    r = response(&fake);
    assert(r.type == DPLS_MSG_TIME_SYNC && r.sequence == 30u && r.payload_length == 0u);
    assert(server.clock.valid);

    auth_payload(&server, payload);
    payload[12] = 3u;
    memcpy(payload + 13, "Lab", 3u);
    n = request(DPLS_MSG_NAME_SET, 31u, payload, 16u, buf);
    assert(dpls_server_receive(&server, buf, n, 10u));
    r = response(&fake);
    assert(r.type == DPLS_MSG_SETTINGS_RESULT && r.sequence == 31u && r.payload_length == 1u && r.payload[0] == 0u);
    assert(strcmp(fake.name, "Lab") == 0);

    auth_payload(&server, payload);
    n = request(DPLS_MSG_LOG_START, 40u, payload, 12u, buf);
    assert(dpls_server_receive(&server, buf, n, 11u));
    r = response(&fake);
    assert(r.type == DPLS_MSG_LOG_INFO && r.sequence == 40u);

    auth_payload(&server, payload);
    payload[12] = 0u; payload[13] = 0u;
    n = request(DPLS_MSG_LOG_ACK, 41u, payload, 14u, buf);
    assert(dpls_server_receive(&server, buf, n, 12u));
    r = response(&fake);
    assert((r.type == DPLS_MSG_LOG_CHUNK || r.type == DPLS_MSG_LOG_RESULT) && r.sequence == 41u);
}

static void test_protocol_and_identify_guards(void) {
    fake_t fake;
    dpls_server_t server;
    dpls_frame_t frame;
    uint8_t buf[DPLS_MAX_FRAME];
    uint8_t garbage[4] = {1u, 2u, 3u, 4u};
    size_t length;

    init_server(&fake, &server, true);
    assert(!dpls_server_receive(&server, garbage, sizeof(garbage), 1u));

    length = request(0x7fu, 50u, 0, 0u, buf);
    assert(dpls_server_receive(&server, buf, length, 2u));
    assert(response_status(&fake, DPLS_MSG_ERROR) == 5u);

    memset(&frame, 0, sizeof(frame));
    frame.type = DPLS_MSG_STATE_GET;
    frame.sequence = 51u;
    length = dpls_frame_encode(&frame, buf, sizeof(buf));
    assert(!dpls_server_receive(&server, buf, length, 3u));
    assert(response_status(&fake, DPLS_MSG_ERROR) == 2u);

    dpls_server_connected(&server, 4u);
    length = request(DPLS_MSG_IDENTIFY_START, 52u, 0, 0u, buf);
    assert(dpls_server_receive(&server, buf, length, 5u));
    assert(fake.identify_active && server.identify.active);

    assert(dpls_server_receive(&server, buf, length, 6u));
    length = request(DPLS_MSG_IDENTIFY_STOP, 53u, 0, 0u, buf);
    assert(dpls_server_receive(&server, buf, length, 7u));
    assert(!fake.identify_active && !server.identify.active);

    assert(dpls_server_receive(&server, buf, length, 8u));
    length = request(DPLS_MSG_IDENTIFY_START, 54u, 0, 0u, buf);
    assert(dpls_server_receive(&server, buf, length, 9u));
    dpls_server_tick(&server, 9u + DPLS_IDENTIFY_MAX_MS);
    assert(!fake.identify_active);

    fake.encrypted = false;
    assert(dpls_server_receive(&server, buf, length, 10u));
    assert(response_status(&fake, DPLS_MSG_ERROR) == 2u);
    dpls_server_disconnected(&server, 11u);
    dpls_server_disconnected(&server, 12u);
}

static void test_device_settings_and_keep_alive(void) {
    fake_t fake;
    dpls_server_t server;
    dpls_frame_t frame;
    uint8_t buf[DPLS_MAX_FRAME];
    uint8_t payload[64];
    size_t length;

    init_server(&fake, &server, true);
    authenticate(&server, &fake, buf);

    auth_payload(&server, payload);
    length = request(DPLS_MSG_DEVICE_INFO_GET, 60u, payload, 12u, buf);
    assert(dpls_server_receive(&server, buf, length, 4u));
    frame = response(&fake);
    assert(frame.type == DPLS_MSG_DEVICE_INFO_REPORT && frame.payload[4] == DPLS_PROTOCOL_VERSION);

    auth_payload(&server, payload);
    length = request(DPLS_MSG_KEEP_ALIVE, 61u, payload, 12u, buf);
    assert(dpls_server_receive(&server, buf, length, 5u));
    assert(response(&fake).type == DPLS_MSG_KEEP_ALIVE);

    auth_payload(&server, payload);
    payload[12] = 0u;
    length = request(DPLS_MSG_NAME_SET, 62u, payload, 13u, buf);
    assert(dpls_server_receive(&server, buf, length, 6u));
    assert(response_status(&fake, DPLS_MSG_SETTINGS_RESULT) == 1u);

    fake.fail_set_name = true;
    payload[12] = 3u;
    memcpy(payload + 13, "Lab", 3u);
    length = request(DPLS_MSG_NAME_SET, 63u, payload, 16u, buf);
    assert(dpls_server_receive(&server, buf, length, 7u));
    assert(response_status(&fake, DPLS_MSG_SETTINGS_RESULT) == 2u);

    auth_payload(&server, payload);
    memset(payload + 12, 0x55, 48u);
    fake.fail_set_password = true;
    length = request(DPLS_MSG_PASSWORD_SET, 64u, payload, 60u, buf);
    assert(dpls_server_receive(&server, buf, length, 8u));
    assert(response_status(&fake, DPLS_MSG_SETTINGS_RESULT) == 2u);

    fake.fail_set_password = false;
    assert(dpls_server_receive(&server, buf, length, 9u));
    assert(response_status(&fake, DPLS_MSG_SETTINGS_RESULT) == 0u);
    assert(!dpls_server_authenticated(&server));
    dpls_server_tick(&server, 509u);
    assert(fake.disconnect_count == 1u);
}

static void test_setup_paths(void) {
    fake_t fake;
    dpls_server_t server;
    uint8_t buf[DPLS_MAX_FRAME];
    uint8_t payload[64] = {0};
    size_t length;

    init_server(&fake, &server, false);
    dpls_server_connected(&server, 1u);
    length = request(DPLS_MSG_HELLO, 70u, payload, DPLS_AUTH_NONCE_SIZE, buf);
    assert(dpls_server_receive(&server, buf, length, 2u));

    payload[0] = (uint8_t)server.session.session_id;
    payload[1] = (uint8_t)(server.session.session_id >> 8);
    payload[2] = (uint8_t)(server.session.session_id >> 16);
    payload[3] = (uint8_t)(server.session.session_id >> 24);
    payload[4] = 0u;
    length = request(DPLS_MSG_SETUP, 71u, payload, 54u, buf);
    assert(dpls_server_receive(&server, buf, length, 3u));
    assert(response_status(&fake, DPLS_MSG_ERROR) == 3u);

    payload[4] = 3u;
    memcpy(payload + 5, "Lab", 3u);
    memset(payload + 8, 0x22, DPLS_AUTH_SALT_SIZE + DPLS_AUTH_PROOF_SIZE);
    length = request(DPLS_MSG_SETUP, 72u, payload, 56u, buf);
    fake.fail_settings_write = true;
    assert(dpls_server_receive(&server, buf, length, 4u));
    assert(response_status(&fake, DPLS_MSG_ERROR) == 4u);

    fake.fail_settings_write = false;
    assert(dpls_server_receive(&server, buf, length, 5u));
    assert(response_status(&fake, DPLS_MSG_AUTH_RESULT) == 3u);
    dpls_server_tick(&server, 505u);
    assert(fake.disconnect_count == 1u);
}

static void test_auth_lockout_and_rng_failure(void) {
    fake_t fake;
    dpls_server_t server;
    uint8_t buf[DPLS_MAX_FRAME];
    uint8_t payload[48] = {0};
    size_t length;
    uint32_t now;
    unsigned attempt;

    init_server(&fake, &server, true);
    fake.reject_proof = true;
    dpls_server_connected(&server, 1u);
    length = request(DPLS_MSG_HELLO, 80u, payload, DPLS_AUTH_NONCE_SIZE, buf);
    assert(dpls_server_receive(&server, buf, length, 2u));
    length = request(DPLS_MSG_AUTH_PROOF, 81u, payload, sizeof(payload), buf);
    for (attempt = 0u; attempt < DPLS_AUTH_MAX_ATTEMPTS; ++attempt) {
        now = 3u + attempt * DPLS_AUTH_MIN_INTERVAL_MS;
        assert(dpls_server_receive(&server, buf, length, now));
    }
    assert(fake.auth_locked);
    assert(response_status(&fake, DPLS_MSG_AUTH_RESULT) == 2u);
    assert(dpls_server_receive(&server, buf, length, now + 1u));
    assert(response_status(&fake, DPLS_MSG_AUTH_RESULT) == 2u);

    init_server(&fake, &server, true);
    dpls_server_connected(&server, 1u);
    length = request(DPLS_MSG_HELLO, 82u, payload, DPLS_AUTH_NONCE_SIZE, buf);
    assert(dpls_server_receive(&server, buf, length, 2u));
    fake.fail_random = true;
    length = request(DPLS_MSG_AUTH_PROOF, 83u, payload, sizeof(payload), buf);
    assert(dpls_server_receive(&server, buf, length, 3u));
    assert(server.critical_fault && fake.diagnostic_count == 1u);
    assert(response_status(&fake, DPLS_MSG_AUTH_RESULT) == 4u);
}

static void test_mode_failures_and_safety_returns(void) {
    fake_t fake;
    dpls_server_t server;
    uint8_t buf[DPLS_MAX_FRAME];
    uint8_t payload[13];
    size_t length;

    init_server(&fake, &server, true);
    authenticate(&server, &fake, buf);
    auth_payload(&server, payload);
    payload[12] = DPLS_MODE_OPEN_T;

    fake.fail_apply = true;
    length = request(DPLS_MSG_MODE_SET, 90u, payload, sizeof(payload), buf);
    assert(dpls_server_receive(&server, buf, length, 4u));
    assert(response_status(&fake, DPLS_MSG_COMMAND_RESULT) == 4u);
    assert(server.safety.mode == DPLS_MODE_NORMAL);

    fake.fail_apply = false;
    fake.short_active = true;
    dpls_server_tick(&server, 5u);
    length = request(DPLS_MSG_MODE_SET, 91u, payload, sizeof(payload), buf);
    assert(dpls_server_receive(&server, buf, length, 6u));
    assert(response_status(&fake, DPLS_MSG_COMMAND_RESULT) == 5u);

    fake.short_active = false;
    dpls_server_tick(&server, 7u);
    length = request(DPLS_MSG_MODE_SET, 92u, payload, sizeof(payload), buf);
    assert(dpls_server_receive(&server, buf, length, 8u));
    fake.low_reserve = true;
    dpls_server_tick(&server, 9u);
    assert(server.safety.mode == DPLS_MODE_NORMAL);
}

static void test_tick_samples_hardware_once(void) {
    fake_t fake;
    dpls_server_t server;
    init_server(&fake, &server, true);
    dpls_server_tick(&server, 1u);
    assert(fake.power_read_count == 1u);
    assert(fake.reserve_read_count == 1u);
    assert(fake.short_read_count == 1u);
    assert(sizeof(dpls_server_t) < 1024u);
}

int main(void) {
    test_core_flow();
    test_protocol_and_identify_guards();
    test_device_settings_and_keep_alive();
    test_setup_paths();
    test_auth_lockout_and_rng_failure();
    test_mode_failures_and_safety_returns();
    test_tick_samples_hardware_once();

    puts("dpls server v2 invariant tests passed");
    return 0;
}
