#include "dpls_server.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

#define FULL_SAFETY_VALIDITY \
    (DPLS_STATE_LINE_VOLTAGE_VALID | DPLS_STATE_RESERVE_VOLTAGE_VALID | \
     DPLS_STATE_POWER_VALID | DPLS_STATE_AUTOISO_VALID)

enum { AUTH_INTERNAL_ERROR = 4 };

typedef struct {
    bool encrypted;
    bool reject_proof;
    bool lock_present;
    bool fail_lock_set;
    bool fail_lock_clear;
    unsigned lock_set_attempts;
    unsigned lock_clear_attempts;
    unsigned disconnect_count;
    unsigned diagnostic_count;
    unsigned normal_count;
    uint8_t tx[DPLS_MAX_FRAME];
    size_t tx_len;
    dpls_event_t events[DPLS_EVENT_CAPACITY];
    uint16_t event_count;
    uint32_t next_sequence;
} fake_t;

static bool encrypted(void *context)
{
    return ((fake_t *)context)->encrypted;
}

static bool indicate(void *context, const uint8_t *frame, size_t length)
{
    fake_t *fake = context;
    assert(length <= sizeof(fake->tx));
    memcpy(fake->tx, frame, length);
    fake->tx_len = length;
    return true;
}

static void disconnect_link(void *context)
{
    ++((fake_t *)context)->disconnect_count;
}

static bool apply_mode(void *context, dpls_mode_t mode)
{
    (void)context;
    (void)mode;
    return true;
}

static void safe_normal(void *context)
{
    ++((fake_t *)context)->normal_count;
}

static uint16_t voltage(void *context)
{
    (void)context;
    return 24000u;
}

static dpls_power_t power_source(void *context)
{
    (void)context;
    return DPLS_POWER_LINE;
}

static bool false_input(void *context)
{
    (void)context;
    return false;
}

static uint8_t measurement_validity(void *context)
{
    (void)context;
    return FULL_SAFETY_VALIDITY;
}

static void identify_led(void *context, bool enabled)
{
    (void)context;
    (void)enabled;
}

static void device_info(void *context, dpls_device_info_t *out)
{
    (void)context;
    memset(out, 0, sizeof(*out));
}

static dpls_settings_state_t settings_state(void *context)
{
    (void)context;
    return DPLS_SETTINGS_VALID;
}

static void settings_salt(void *context, uint8_t out[DPLS_AUTH_SALT_SIZE])
{
    (void)context;
    memset(out, 0x22, DPLS_AUTH_SALT_SIZE);
}

static bool settings_write(void *context, const char *name, const uint8_t salt[16],
                           const uint8_t verifier[32])
{
    (void)context;
    (void)name;
    (void)salt;
    (void)verifier;
    return true;
}

static void settings_name(void *context, char out[DPLS_NAME_MAX + 1u])
{
    (void)context;
    memcpy(out, "Test-DPLS", 10u);
    out[9] = '\0';
}

static bool settings_set_name(void *context, const char *name)
{
    (void)context;
    (void)name;
    return true;
}

static bool settings_set_password(void *context, const uint8_t salt[16],
                                  const uint8_t verifier[32])
{
    (void)context;
    (void)salt;
    (void)verifier;
    return true;
}

static bool random_bytes(void *context, uint8_t *out, size_t length)
{
    size_t i;
    (void)context;
    for (i = 0u; i < length; ++i) out[i] = (uint8_t)(i + 1u);
    return true;
}

static bool verify_proof(void *context, const uint8_t device_nonce[16],
                         const uint8_t client_nonce[16], uint32_t session_id,
                         const uint8_t proof[32])
{
    (void)device_nonce;
    (void)client_nonce;
    (void)session_id;
    (void)proof;
    return !((fake_t *)context)->reject_proof;
}

static bool lock_read(void *context)
{
    return ((fake_t *)context)->lock_present;
}

static bool lock_write(void *context, bool locked)
{
    fake_t *fake = context;
    if (locked) {
        ++fake->lock_set_attempts;
        if (fake->fail_lock_set) return false;
        fake->lock_present = true;
    } else {
        ++fake->lock_clear_attempts;
        if (fake->fail_lock_clear) return false;
        fake->lock_present = false;
    }
    return true;
}

static bool event_init(void *context, uint16_t *count, uint32_t *next_sequence)
{
    fake_t *fake = context;
    *count = fake->event_count;
    *next_sequence = fake->next_sequence ? fake->next_sequence : 1u;
    return true;
}

static bool event_append(void *context, const dpls_event_t *event)
{
    fake_t *fake = context;
    if (fake->event_count == DPLS_EVENT_CAPACITY) {
        memmove(fake->events, fake->events + 1,
                (DPLS_EVENT_CAPACITY - 1u) * sizeof(fake->events[0]));
        --fake->event_count;
    }
    fake->events[fake->event_count++] = *event;
    fake->next_sequence = event->sequence + 1u;
    return true;
}

static bool event_read(void *context, uint32_t sequence, dpls_event_t *out)
{
    fake_t *fake = context;
    uint16_t i;
    for (i = 0u; i < fake->event_count; ++i) {
        if (fake->events[i].sequence == sequence) {
            *out = fake->events[i];
            return true;
        }
    }
    return false;
}

static void diagnostic_error(void *context, bool critical)
{
    fake_t *fake = context;
    assert(critical);
    ++fake->diagnostic_count;
}

static dpls_hal_t make_hal(fake_t *fake)
{
    dpls_hal_t hal;
    memset(&hal, 0, sizeof(hal));
    hal.link.encrypted = encrypted;
    hal.link.indicate = indicate;
    /* Spy only: physical disconnect belongs to runtime and must remain untouched here. */
    hal.link.disconnect = disconnect_link;
    hal.hardware.apply_mode = apply_mode;
    hal.hardware.safe_normal = safe_normal;
    hal.hardware.voltage_mv = voltage;
    hal.hardware.port1_voltage_mv = voltage;
    hal.hardware.port2_voltage_mv = voltage;
    hal.hardware.port_t_voltage_mv = voltage;
    hal.hardware.reserve_voltage_mv = voltage;
    hal.hardware.power_source = power_source;
    hal.hardware.reserve_low = false_input;
    hal.hardware.measurement_validity = measurement_validity;
    hal.hardware.identify_led = identify_led;
    hal.hardware.real_short_active = false_input;
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
    hal.events.init = event_init;
    hal.events.append = event_append;
    hal.events.read = event_read;
    hal.context = fake;
    hal.diagnostic_error = diagnostic_error;
    return hal;
}

static size_t request(uint8_t type, uint16_t sequence, const uint8_t *payload,
                      uint16_t payload_length, uint8_t out[DPLS_MAX_FRAME])
{
    dpls_frame_t frame;
    memset(&frame, 0, sizeof(frame));
    frame.type = type;
    frame.flags = DPLS_FLAG_REQUEST;
    frame.sequence = sequence;
    frame.payload_length = payload_length;
    if (payload_length != 0u) memcpy(frame.payload, payload, payload_length);
    return dpls_frame_encode(&frame, out, DPLS_MAX_FRAME);
}

static dpls_frame_t last_response(const fake_t *fake)
{
    dpls_frame_t frame;
    assert(fake->tx_len != 0u);
    assert(dpls_frame_decode(fake->tx, fake->tx_len, &frame));
    assert((frame.flags & DPLS_FLAG_RESPONSE) != 0u);
    return frame;
}

static void start_auth(dpls_server_t *server, fake_t *fake, uint8_t frame[DPLS_MAX_FRAME])
{
    uint8_t hello[DPLS_AUTH_NONCE_SIZE] = {0};
    size_t length;
    dpls_frame_t response;
    dpls_server_connected(server, 1u);
    length = request(DPLS_MSG_HELLO, 1u, hello, sizeof(hello), frame);
    assert(dpls_server_receive(server, frame, length, 2u));
    response = last_response(fake);
    assert(response.type == DPLS_MSG_AUTH_CHALLENGE);
}

static void test_lock_set_failure_is_critical(void)
{
    fake_t fake;
    dpls_hal_t hal;
    dpls_server_t server;
    uint8_t frame[DPLS_MAX_FRAME];
    uint8_t proof[DPLS_AUTH_NONCE_SIZE + DPLS_AUTH_PROOF_SIZE] = {0};
    dpls_frame_t response;
    size_t length;
    unsigned attempt;

    memset(&fake, 0, sizeof(fake));
    fake.encrypted = true;
    fake.reject_proof = true;
    fake.fail_lock_set = true;
    hal = make_hal(&fake);
    dpls_server_init(&server, &hal, 0u);
    start_auth(&server, &fake, frame);

    length = request(DPLS_MSG_AUTH_PROOF, 2u, proof, sizeof(proof), frame);
    for (attempt = 0u; attempt < DPLS_AUTH_MAX_ATTEMPTS; ++attempt) {
        uint32_t now = 3u + attempt * DPLS_AUTH_MIN_INTERVAL_MS;
        assert(dpls_server_receive(&server, frame, length, now));
    }

    response = last_response(&fake);
    assert(response.type == DPLS_MSG_AUTH_RESULT);
    assert(response.payload_length == 11u);
    assert(response.payload[0] == AUTH_INTERNAL_ERROR);
    assert(fake.lock_set_attempts == 1u);
    assert(!fake.lock_present);
    assert(server.critical_fault);
    assert(server.safety.mode == DPLS_MODE_NORMAL);
    assert(!dpls_server_authenticated(&server));
    assert(fake.disconnect_count == 0u);
    assert(fake.diagnostic_count == 1u);
}

static void test_expired_lock_clear_failure_stays_closed(void)
{
    fake_t fake;
    dpls_hal_t hal;
    dpls_server_t server;
    uint8_t frame[DPLS_MAX_FRAME];
    uint8_t proof[DPLS_AUTH_NONCE_SIZE + DPLS_AUTH_PROOF_SIZE] = {0};
    size_t length;

    memset(&fake, 0, sizeof(fake));
    fake.encrypted = true;
    fake.lock_present = true;
    fake.fail_lock_clear = true;
    hal = make_hal(&fake);
    dpls_server_init(&server, &hal, 0u);
    start_auth(&server, &fake, frame);

    length = request(DPLS_MSG_AUTH_PROOF, 2u, proof, sizeof(proof), frame);
    assert(dpls_server_receive(&server, frame, length, DPLS_AUTH_BLOCK_MS + 1u));

    assert(fake.lock_clear_attempts == 1u);
    assert(fake.lock_present);
    assert(server.critical_fault);
    assert(server.session.blocked_until_ms != 0u);
    assert(server.session.failed_auth_attempts == DPLS_AUTH_MAX_ATTEMPTS);
    assert(!dpls_server_authenticated(&server));
    assert(fake.disconnect_count == 0u);
    assert(fake.diagnostic_count == 1u);
}

static void test_success_cannot_bypass_unclearable_lock(void)
{
    fake_t fake;
    dpls_hal_t hal;
    dpls_server_t server;
    uint8_t frame[DPLS_MAX_FRAME];
    uint8_t proof[DPLS_AUTH_NONCE_SIZE + DPLS_AUTH_PROOF_SIZE] = {0};
    dpls_frame_t response;
    size_t length;

    memset(&fake, 0, sizeof(fake));
    fake.encrypted = true;
    fake.fail_lock_clear = true;
    hal = make_hal(&fake);
    dpls_server_init(&server, &hal, 0u);
    start_auth(&server, &fake, frame);

    length = request(DPLS_MSG_AUTH_PROOF, 2u, proof, sizeof(proof), frame);
    assert(dpls_server_receive(&server, frame, length, 3u));

    response = last_response(&fake);
    assert(response.type == DPLS_MSG_AUTH_RESULT);
    assert(response.payload_length == 11u);
    assert(response.payload[0] == AUTH_INTERNAL_ERROR);
    assert(fake.lock_clear_attempts == 1u);
    assert(server.critical_fault);
    assert(!dpls_server_authenticated(&server));
    assert(fake.disconnect_count == 0u);
    assert(fake.diagnostic_count == 1u);
}

int main(void)
{
    test_lock_set_failure_is_critical();
    test_expired_lock_clear_failure_stays_closed();
    test_success_cannot_bypass_unclearable_lock();
    puts("auth lock fail-safe tests: ok");
    return 0;
}
