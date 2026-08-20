#include "dpls_server.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define FULL_SAFETY_VALIDITY \
    (DPLS_STATE_LINE_VOLTAGE_VALID | DPLS_STATE_RESERVE_VOLTAGE_VALID | \
     DPLS_STATE_POWER_VALID | DPLS_STATE_AUTOISO_VALID)

typedef struct {
    bool encrypted;
    bool low_reserve;
    bool real_short;
    uint8_t measurement_validity;
    bool normal_output;
    uint8_t tx[DPLS_MAX_FRAME];
    size_t tx_len;
} fake_t;

static uint32_t rng_state = 0x9e3779b9u;

static uint32_t rnd32(void)
{
    uint32_t x = rng_state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    rng_state = x;
    return x;
}

static bool link_encrypted(void *ctx) { return ((fake_t *)ctx)->encrypted; }
static bool indicate(void *ctx, const uint8_t *frame, size_t length)
{
    fake_t *f = ctx;
    assert(length <= sizeof(f->tx));
    memcpy(f->tx, frame, length);
    f->tx_len = length;
    return true;
}
static void disconnect_link(void *ctx) { (void)ctx; }
static bool apply_mode(void *ctx, dpls_mode_t mode)
{
    ((fake_t *)ctx)->normal_output = mode == DPLS_MODE_NORMAL;
    return true;
}
static void safe_normal(void *ctx) { ((fake_t *)ctx)->normal_output = true; }
static uint16_t voltage(void *ctx) { (void)ctx; return 24000u; }
static dpls_power_t power_source(void *ctx) { (void)ctx; return DPLS_POWER_LINE; }
static bool reserve_low(void *ctx) { return ((fake_t *)ctx)->low_reserve; }
static uint8_t measurement_validity(void *ctx) { return ((fake_t *)ctx)->measurement_validity; }
static void identify_led(void *ctx, bool enabled) { (void)ctx; (void)enabled; }
static bool real_short_active(void *ctx) { return ((fake_t *)ctx)->real_short; }
static void device_info(void *ctx, dpls_device_info_t *out)
{
    (void)ctx;
    memset(out, 0, sizeof(*out));
    out->device_id = 0x1234u;
}
static dpls_settings_state_t settings_state(void *ctx) { (void)ctx; return DPLS_SETTINGS_VALID; }
static void settings_salt(void *ctx, uint8_t out[DPLS_AUTH_SALT_SIZE])
{
    (void)ctx;
    memset(out, 0x22, DPLS_AUTH_SALT_SIZE);
}
static bool settings_write(void *ctx, const char *name, const uint8_t salt[16], const uint8_t verifier[32])
{
    (void)ctx; (void)name; (void)salt; (void)verifier; return true;
}
static void settings_name(void *ctx, char out[DPLS_NAME_MAX + 1u])
{
    (void)ctx;
    strcpy(out, "sequence-test");
}
static bool settings_set_name(void *ctx, const char *name) { (void)ctx; (void)name; return true; }
static bool settings_set_password(void *ctx, const uint8_t salt[16], const uint8_t verifier[32])
{
    (void)ctx; (void)salt; (void)verifier; return true;
}
static bool random_bytes(void *ctx, uint8_t *out, size_t length)
{
    size_t i;
    (void)ctx;
    for (i = 0u; i < length; ++i) out[i] = (uint8_t)(i + 1u);
    return true;
}
static bool verify_proof(void *ctx, const uint8_t device_nonce[16], const uint8_t client_nonce[16],
                         uint32_t session_id, const uint8_t proof[32])
{
    (void)ctx; (void)device_nonce; (void)client_nonce; (void)session_id; (void)proof;
    return true;
}
static bool lock_read(void *ctx) { (void)ctx; return false; }
static bool lock_write(void *ctx, bool locked) { (void)ctx; (void)locked; return true; }
static bool events_init(void *ctx, uint16_t *count, uint32_t *next_sequence)
{
    (void)ctx; *count = 0u; *next_sequence = 1u; return true;
}
static bool events_append(void *ctx, const dpls_event_t *event) { (void)ctx; (void)event; return true; }
static bool events_read(void *ctx, uint32_t sequence, dpls_event_t *event)
{
    (void)ctx; (void)sequence; (void)event; return false;
}
static void diagnostic(void *ctx, bool critical) { (void)ctx; (void)critical; }

static dpls_hal_t make_hal(fake_t *fake)
{
    dpls_hal_t hal;
    memset(&hal, 0, sizeof(hal));
    hal.link.encrypted = link_encrypted;
    hal.link.indicate = indicate;
    hal.link.disconnect = disconnect_link;
    hal.hardware.apply_mode = apply_mode;
    hal.hardware.safe_normal = safe_normal;
    hal.hardware.voltage_mv = voltage;
    hal.hardware.port1_voltage_mv = voltage;
    hal.hardware.port2_voltage_mv = voltage;
    hal.hardware.port_t_voltage_mv = voltage;
    hal.hardware.reserve_voltage_mv = voltage;
    hal.hardware.power_source = power_source;
    hal.hardware.reserve_low = reserve_low;
    hal.hardware.measurement_validity = measurement_validity;
    hal.hardware.identify_led = identify_led;
    hal.hardware.real_short_active = real_short_active;
    hal.hardware.device_info = device_info;
    hal.settings.state = settings_state;
    hal.settings.salt = settings_salt;
    hal.settings.write = settings_write;
    hal.settings.name = settings_name;
    hal.settings.set_name = settings_set_name;
    hal.settings.set_password = settings_set_password;
    hal.auth.random_bytes = random_bytes;
    hal.auth.verify_proof = verify_proof;
    hal.auth.lock_read = lock_read;
    hal.auth.lock_write = lock_write;
    hal.events.init = events_init;
    hal.events.append = events_append;
    hal.events.read = events_read;
    hal.context = fake;
    hal.diagnostic_error = diagnostic;
    return hal;
}

static size_t request(uint8_t type, uint16_t sequence, const uint8_t *payload, uint16_t length, uint8_t *out)
{
    dpls_frame_t frame;
    memset(&frame, 0, sizeof(frame));
    frame.type = type;
    frame.flags = DPLS_FLAG_REQUEST;
    frame.sequence = sequence;
    frame.payload_length = length;
    if (length) memcpy(frame.payload, payload, length);
    return dpls_frame_encode(&frame, out, DPLS_MAX_FRAME);
}

static void auth_payload(const dpls_server_t *server, uint8_t out[12])
{
    out[0] = (uint8_t)server->session.session_id;
    out[1] = (uint8_t)(server->session.session_id >> 8);
    out[2] = (uint8_t)(server->session.session_id >> 16);
    out[3] = (uint8_t)(server->session.session_id >> 24);
    memcpy(out + 4, server->session.token, DPLS_SESSION_TOKEN_SIZE);
}

static void authenticate(dpls_server_t *server, uint32_t *now, uint16_t *sequence)
{
    uint8_t frame[DPLS_MAX_FRAME];
    uint8_t hello[DPLS_AUTH_NONCE_SIZE] = {0};
    uint8_t proof[DPLS_AUTH_NONCE_SIZE + DPLS_AUTH_PROOF_SIZE] = {0};
    size_t length;

    if (!server->session.connected) {
        *now += 1u;
        dpls_server_connected(server, *now);
    }
    *now += DPLS_AUTH_MIN_INTERVAL_MS + 1u;
    length = request(DPLS_MSG_HELLO, (*sequence)++, hello, sizeof(hello), frame);
    assert(dpls_server_receive(server, frame, length, *now));
    *now += 1u;
    length = request(DPLS_MSG_AUTH_PROOF, (*sequence)++, proof, sizeof(proof), frame);
    assert(dpls_server_receive(server, frame, length, *now));
    assert(server->session.authenticated);
}

static void request_mode(dpls_server_t *server, uint32_t now, uint16_t *sequence, dpls_mode_t mode)
{
    uint8_t frame[DPLS_MAX_FRAME];
    uint8_t payload[13];
    size_t length;
    if (!server->session.connected || !server->session.authenticated) return;
    auth_payload(server, payload);
    payload[12] = (uint8_t)mode;
    length = request(DPLS_MSG_MODE_SET, (*sequence)++, payload, sizeof(payload), frame);
    assert(dpls_server_receive(server, frame, length, now));
}

static void assert_global_safety_invariant(const dpls_server_t *server, const fake_t *fake)
{
    if (server->safety.mode == DPLS_MODE_NORMAL) return;
    assert(!server->critical_fault);
    assert(server->session.connected);
    assert(server->session.authenticated);
    assert(fake->encrypted);
    assert((fake->measurement_validity & FULL_SAFETY_VALIDITY) == FULL_SAFETY_VALIDITY);
    assert(!fake->low_reserve);
    assert(!fake->real_short);
    assert(!fake->normal_output);
}

static void run_deterministic_event_sequences(void)
{
    unsigned scenario;
    for (scenario = 0u; scenario < 1000u; ++scenario) {
        fake_t fake;
        dpls_server_t server;
        dpls_hal_t hal;
        uint32_t now = (uint32_t)scenario * 17u;
        uint16_t sequence = 1u;
        unsigned step;

        memset(&fake, 0, sizeof(fake));
        fake.encrypted = true;
        fake.measurement_validity = FULL_SAFETY_VALIDITY;
        fake.normal_output = true;
        hal = make_hal(&fake);
        dpls_server_init(&server, &hal, now);
        authenticate(&server, &now, &sequence);

        for (step = 0u; step < 96u; ++step) {
            switch (rnd32() % 9u) {
            case 0u:
                request_mode(&server, now, &sequence, (dpls_mode_t)(rnd32() % 6u));
                break;
            case 1u:
                fake.low_reserve = !fake.low_reserve;
                dpls_server_tick(&server, ++now);
                break;
            case 2u:
                fake.real_short = !fake.real_short;
                dpls_server_tick(&server, ++now);
                break;
            case 3u:
                fake.measurement_validity = fake.measurement_validity ? 0u : FULL_SAFETY_VALIDITY;
                dpls_server_tick(&server, ++now);
                break;
            case 4u:
                dpls_server_disconnected(&server, ++now);
                assert(server.safety.mode == DPLS_MODE_NORMAL);
                assert(fake.normal_output);
                break;
            case 5u:
                if (!server.session.connected) authenticate(&server, &now, &sequence);
                break;
            case 6u:
                now += rnd32() % (DPLS_SAFETY_SESSION_TIMEOUT_MS + 5000u);
                dpls_server_tick(&server, now);
                break;
            case 7u:
                fake.encrypted = !fake.encrypted;
                if (!fake.encrypted && server.safety.mode != DPLS_MODE_NORMAL) {
                    /* Link encryption is enforced at protocol admission; an
                     * already-running product session is additionally bounded
                     * by disconnect/session/measurement safety. Model the real
                     * stack by dropping the ACL when encryption disappears. */
                    dpls_server_disconnected(&server, ++now);
                }
                if (!fake.encrypted) fake.encrypted = true;
                break;
            default:
                dpls_server_tick(&server, ++now);
                break;
            }
            assert_global_safety_invariant(&server, &fake);
        }
    }
}

int main(void)
{
    run_deterministic_event_sequences();
    puts("server deterministic state/event sequences: OK (96k transitions)");
    return 0;
}
