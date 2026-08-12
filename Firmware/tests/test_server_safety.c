#include "dpls_server.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    bool encrypted;
    bool low_reserve;
    bool apply_fail;
    uint8_t validity;
    dpls_mode_t hw_mode;
    unsigned safe_normal_count;
    unsigned apply_count;
    unsigned critical_count;
    uint8_t tx[DPLS_MAX_FRAME];
    size_t tx_len;
    dpls_event_t events[32];
    unsigned event_count;
} fake_t;

static bool encrypted(void *c) { return ((fake_t *)c)->encrypted; }
static bool apply(void *c, dpls_mode_t mode)
{
    fake_t *f = c;
    ++f->apply_count;
    if (f->apply_fail) return false;
    f->hw_mode = mode;
    return true;
}
static void normal(void *c)
{
    fake_t *f = c;
    ++f->safe_normal_count;
    f->hw_mode = DPLS_MODE_NORMAL;
}
static uint16_t voltage(void *c) { (void)c; return 24000u; }
static dpls_power_t power(void *c) { (void)c; return DPLS_POWER_LINE; }
static bool low(void *c) { return ((fake_t *)c)->low_reserve; }
static uint8_t validity(void *c) { return ((fake_t *)c)->validity; }
static bool real_short(void *c) { (void)c; return false; }
static void identify(void *c, bool on) { (void)c; (void)on; }
static bool random_bytes(void *c, uint8_t *out, size_t length)
{
    (void)c;
    memset(out, 0x55, length);
    return true;
}
static dpls_settings_state_t settings_state(void *c) { (void)c; return DPLS_SETTINGS_VALID; }
static void salt(void *c, uint8_t out[16]) { (void)c; memset(out, 0x11, 16); }
static bool verify(void *c, const uint8_t d[16], const uint8_t n[16], uint32_t id, const uint8_t proof[32])
{
    (void)c; (void)d; (void)n; (void)id;
    return proof[0] == 0xa5u;
}
static bool indicate(void *c, const uint8_t *frame, size_t length)
{
    fake_t *f = c;
    memcpy(f->tx, frame, length);
    f->tx_len = length;
    return true;
}
static bool append_event(void *c, const dpls_event_t *event)
{
    fake_t *f = c;
    if (f->event_count < sizeof(f->events) / sizeof(f->events[0]))
        f->events[f->event_count] = *event;
    ++f->event_count;
    return true;
}
static void diagnostic(void *c, bool critical)
{
    if (critical) ++((fake_t *)c)->critical_count;
}

static dpls_hal_t make_hal(fake_t *f)
{
    dpls_hal_t hal;
    memset(&hal, 0, sizeof(hal));
    hal.link_encrypted = encrypted;
    hal.hardware_apply_mode = apply;
    hal.hardware_safe_normal = normal;
    hal.voltage_mv = voltage;
    hal.port1_voltage_mv = voltage;
    hal.port2_voltage_mv = voltage;
    hal.port_t_voltage_mv = voltage;
    hal.reserve_voltage_mv = voltage;
    hal.power_source = power;
    hal.reserve_low = low;
    hal.measurement_validity = validity;
    hal.real_short_active = real_short;
    hal.identify_led = identify;
    hal.random_bytes = random_bytes;
    hal.settings_state = settings_state;
    hal.settings_salt = salt;
    hal.verify_auth_proof = verify;
    hal.event_storage_append = append_event;
    hal.tx_indicate = indicate;
    hal.context = f;
    hal.diagnostic_error = diagnostic;
    return hal;
}

static size_t request(uint8_t type, const uint8_t *payload, uint16_t length, uint8_t *out)
{
    dpls_frame_t frame;
    memset(&frame, 0, sizeof(frame));
    frame.type = type;
    frame.sequence = 1u;
    frame.payload_length = length;
    if (length) memcpy(frame.payload, payload, length);
    return dpls_frame_encode(&frame, out, DPLS_MAX_FRAME);
}

static void authenticate(dpls_server_t *server, fake_t *fake, uint8_t *buf)
{
    uint8_t payload[48];
    size_t n;
    dpls_hal_t hal = make_hal(fake);
    dpls_server_init(server, &hal, 0u);
    dpls_server_connected(server, 100u);
    memset(payload, 1, 16);
    n = request(DPLS_MSG_HELLO, payload, 16, buf);
    assert(dpls_server_receive(server, buf, n, 200u));
    memset(payload, 1, 16);
    memset(payload + 16, 0, 32);
    payload[16] = 0xa5u;
    n = request(DPLS_MSG_AUTH_PROOF, payload, 48, buf);
    assert(dpls_server_receive(server, buf, n, 1200u));
    assert(server->authenticated);
}

static uint8_t set_mode(dpls_server_t *server, fake_t *fake, uint8_t *buf,
                        uint32_t command_id, dpls_mode_t mode, uint32_t now)
{
    uint8_t payload[17];
    dpls_frame_t response;
    size_t n;
    memcpy(payload, &server->session_id, 4);
    memcpy(payload + 4, server->session_token, 8);
    payload[12] = (uint8_t)command_id;
    payload[13] = (uint8_t)(command_id >> 8);
    payload[14] = (uint8_t)(command_id >> 16);
    payload[15] = (uint8_t)(command_id >> 24);
    payload[16] = (uint8_t)mode;
    n = request(DPLS_MSG_MODE_SET, payload, sizeof(payload), buf);
    assert(dpls_server_receive(server, buf, n, now));
    assert(dpls_frame_decode(fake->tx, fake->tx_len, &response));
    assert(response.type == DPLS_MSG_COMMAND_RESULT);
    return response.payload[4];
}

static void test_active_mode_requires_fresh_reserve(void)
{
    fake_t fake = {.encrypted = true};
    dpls_server_t server;
    uint8_t buf[DPLS_MAX_FRAME];

    authenticate(&server, &fake, buf);

    /* No reserve conversion yet: fail closed without touching an active output. */
    fake.validity = 0u;
    assert(set_mode(&server, &fake, buf, 1u, DPLS_MODE_SHORT_1, 2000u) == 4u);
    assert(fake.apply_count == 0u);
    assert(server.mode == DPLS_MODE_NORMAL);

    /* A fresh but low reserve is the same safety interlock. */
    fake.validity = DPLS_STATE_RESERVE_VALID | DPLS_STATE_POWER_VALID | DPLS_STATE_AUTOISO_VALID;
    fake.low_reserve = true;
    assert(set_mode(&server, &fake, buf, 2u, DPLS_MODE_SHORT_1, 3000u) == 4u);
    assert(fake.apply_count == 0u);

    fake.low_reserve = false;
    assert(set_mode(&server, &fake, buf, 3u, DPLS_MODE_SHORT_1, 4000u) == 0u);
    assert(server.mode == DPLS_MODE_SHORT_1);
    assert(fake.hw_mode == DPLS_MODE_SHORT_1);
}

static void test_apply_failure_cannot_leave_stale_active_mode(void)
{
    fake_t fake = {.encrypted = true,
                   .validity = DPLS_STATE_RESERVE_VALID | DPLS_STATE_POWER_VALID |
                               DPLS_STATE_AUTOISO_VALID};
    dpls_server_t server;
    uint8_t buf[DPLS_MAX_FRAME];

    authenticate(&server, &fake, buf);
    assert(set_mode(&server, &fake, buf, 10u, DPLS_MODE_SHORT_1, 2000u) == 0u);
    assert(server.mode == DPLS_MODE_SHORT_1);

    fake.apply_fail = true;
    assert(set_mode(&server, &fake, buf, 11u, DPLS_MODE_SHORT_2, 3000u) == 4u);
    assert(server.mode == DPLS_MODE_NORMAL);
    assert(server.mode_deadline_ms == 0u);
    assert(fake.hw_mode == DPLS_MODE_NORMAL);
    assert(server.critical_fault);
    assert(fake.critical_count == 1u);
}

static void test_invalid_measurement_does_not_create_flash_log_transition(void)
{
    fake_t fake = {.encrypted = true, .low_reserve = true};
    dpls_hal_t hal = make_hal(&fake);
    dpls_server_t server;
    unsigned after_boot;

    dpls_server_init(&server, &hal, 0u);
    after_boot = fake.event_count;

    /* Unknown reserve used to be interpreted as low and later logged a phantom
     * "reserve recovered" event when the first ADC sample arrived. */
    fake.validity = 0u;
    dpls_server_tick(&server, 1000u);
    assert(fake.event_count == after_boot);

    fake.validity = DPLS_STATE_RESERVE_VALID;
    fake.low_reserve = false;
    dpls_server_tick(&server, 2000u);
    assert(fake.event_count == after_boot); /* first valid sample establishes baseline */

    fake.low_reserve = true;
    dpls_server_tick(&server, 3000u);
    assert(fake.event_count == after_boot + 1u);
    assert(fake.events[after_boot].event_type == 13u);
    assert(fake.events[after_boot].parameter == 1u);
}

int main(void)
{
    test_active_mode_requires_fresh_reserve();
    test_apply_failure_cannot_leave_stale_active_mode();
    test_invalid_measurement_does_not_create_flash_log_transition();
    puts("test_server_safety: OK");
    return 0;
}
