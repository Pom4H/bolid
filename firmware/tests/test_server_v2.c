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
    unsigned apply_count;
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
    f->normal = mode == DPLS_MODE_NORMAL;
    return true;
}
static void normal(void *c) { ((fake_t *)c)->normal = true; }
static uint16_t voltage(void *c) { (void)c; return 24000u; }
static dpls_power_t power(void *c) { (void)c; return DPLS_POWER_LINE; }
static bool reserve_low(void *c) { return ((fake_t *)c)->low_reserve; }
static uint8_t validity(void *c) { (void)c; return DPLS_STATE_LINE_VOLTAGE_VALID | DPLS_STATE_POWER_VALID; }
static void identify(void *c, bool enabled) { (void)c; (void)enabled; }
static bool random_bytes(void *c, uint8_t *out, size_t length) {
    size_t i;
    (void)c;
    for (i = 0; i < length; ++i) out[i] = (uint8_t)(i + 1u);
    return true;
}
static bool real_short(void *c) { return ((fake_t *)c)->short_active; }
static dpls_settings_state_t settings_state(void *c) {
    return ((fake_t *)c)->initialized ? DPLS_SETTINGS_VALID : DPLS_SETTINGS_EMPTY;
}
static void salt(void *c, uint8_t out[16]) { (void)c; memset(out, 0x22, 16); }
static bool settings_write(void *c, const char *name, const uint8_t salt_bytes[16], const uint8_t verifier[32]) {
    fake_t *f = c;
    (void)salt_bytes;
    (void)verifier;
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
    strncpy(f->name, name, sizeof(f->name) - 1u);
    return true;
}
static bool settings_set_password(void *c, const uint8_t salt_bytes[16], const uint8_t verifier[32]) {
    (void)c; (void)salt_bytes; (void)verifier; return true;
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
    (void)c; (void)device_nonce; (void)client_nonce; (void)session_id; (void)proof; return true;
}
static bool storage_init(void *c, uint16_t *count, uint32_t *next_sequence) {
    fake_t *f = c;
    *count = f->event_count;
    *next_sequence = f->next_sequence ? f->next_sequence : 1u;
    return true;
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
    h.link_encrypted = encrypted;
    h.hardware_apply_mode = apply;
    h.hardware_safe_normal = normal;
    h.voltage_mv = voltage;
    h.port1_voltage_mv = voltage;
    h.power_source = power;
    h.reserve_low = reserve_low;
    h.measurement_validity = validity;
    h.identify_led = identify;
    h.random_bytes = random_bytes;
    h.real_short_active = real_short;
    h.settings_state = settings_state;
    h.settings_salt = salt;
    h.settings_write = settings_write;
    h.settings_name = settings_name;
    h.settings_set_name = settings_set_name;
    h.settings_set_password = settings_set_password;
    h.device_info = device_info;
    h.verify_auth_proof = verify;
    h.event_storage_init = storage_init;
    h.event_storage_append = storage_append;
    h.event_storage_read = storage_read;
    h.tx_indicate = tx;
    h.tx_notify = tx;
    h.context = f;
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
    assert(server->authenticated);
}

static void auth_payload(const dpls_server_t *s, uint8_t out[12]) {
    out[0] = (uint8_t)s->session_id;
    out[1] = (uint8_t)(s->session_id >> 8);
    out[2] = (uint8_t)(s->session_id >> 16);
    out[3] = (uint8_t)(s->session_id >> 24);
    memcpy(out + 4, s->session_token, 8);
}

int main(void) {
    fake_t fake;
    dpls_hal_t h;
    dpls_server_t server;
    dpls_frame_t r;
    uint8_t buf[DPLS_MAX_FRAME];
    uint8_t payload[64];
    size_t n;
    unsigned apply_count;

    memset(&fake, 0, sizeof(fake));
    fake.encrypted = true;
    fake.initialized = true;
    fake.next_sequence = 1u;
    h = hal(&fake);
    dpls_server_init(&server, &h, 0u);
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
    assert(server.wall_clock_valid);

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

    puts("dpls server v2 invariant tests passed");
    return 0;
}
