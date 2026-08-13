#include "dpls_server.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

enum { TST_EVT_BLE_CONNECTED = 2 };

typedef struct {
    bool encrypted, initialized, normal, identify, storage_init_fail;
    dpls_mode_t mode;
    uint8_t tx[DPLS_MAX_FRAME];
    size_t tx_len;
    uint8_t notif[256][DPLS_MAX_FRAME];
    size_t notif_len[256];
    unsigned notification_count;
    dpls_event_t stored[DPLS_EVENT_CAPACITY];
    uint16_t stored_count;
    uint32_t stored_next;
    unsigned storage_append_count;
    unsigned storage_read_count;
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
static bool storage_init(void *c, uint16_t *count, uint32_t *next) {
    fake_t *f = c;
    if (f->storage_init_fail) return false;
    *count = f->stored_count; *next = f->stored_next ? f->stored_next : 1u; return true;
}
static bool storage_append(void *c, const dpls_event_t *event) {
    fake_t *f = c;
    ++f->storage_append_count;
    f->stored[(event->sequence - 1u) % DPLS_EVENT_CAPACITY] = *event;
    if (f->stored_count < DPLS_EVENT_CAPACITY) ++f->stored_count;
    f->stored_next = event->sequence + 1u;
    return true;
}
static bool storage_read(void *c, uint32_t sequence, dpls_event_t *event) {
    fake_t *f = c;
    ++f->storage_read_count;
    *event = f->stored[(sequence - 1u) % DPLS_EVENT_CAPACITY];
    return event->sequence == sequence;
}

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
    memset(payload, 1, 16); /* AUTH_PROOF repeats the HELLO client nonce. */
    memset(payload + 16, 0, 32);
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
        uint16_t first;
        uint8_t chunk_count, j;
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
        /* Batched chunk: first_index (u16), count (u8), count × 10-byte events. */
        first = (uint16_t)(frame.payload[0] | (frame.payload[1] << 8));
        chunk_count = frame.payload[2];
        assert(chunk_count >= 1u && chunk_count <= DPLS_LOG_CHUNK_EVENTS);
        for (j = 0; j < chunk_count && count < max_out; ++j) {
            const uint8_t *ev = &frame.payload[3u + (uint16_t)j * 10u];
            out[count].sequence = (uint32_t)ev[0] | ((uint32_t)ev[1] << 8) |
                                  ((uint32_t)ev[2] << 16) | ((uint32_t)ev[3] << 24);
            out[count].timestamp_seconds = (uint32_t)ev[4] | ((uint32_t)ev[5] << 8) |
                                           ((uint32_t)ev[6] << 16) | ((uint32_t)ev[7] << 24);
            out[count].event_type = ev[8];
            out[count].parameter = ev[9];
            ++count;
        }
        next = (uint16_t)(first + chunk_count);
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
        .event_storage_init = storage_init, .event_storage_append = storage_append,
        .event_storage_read = storage_read,
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

static void test_failed_storage_init_disables_journal_for_runtime(void) {
    fake_t fake = {.encrypted = true, .initialized = true, .storage_init_fail = true};
    dpls_hal_t hal = {
        .link_encrypted = encrypted, .hardware_apply_mode = apply, .hardware_safe_normal = normal,
        .voltage_mv = voltage, .power_source = power, .reserve_low = low, .identify_led = identify,
        .random_bytes = random_bytes, .settings_state = settings_state, .settings_salt = salt,
        .settings_write = settings, .verify_auth_proof = verify,
        .event_storage_init = storage_init, .event_storage_append = storage_append,
        .event_storage_read = storage_read,
        .tx_indicate = indicate, .tx_notify = notify, .context = &fake,
    };
    dpls_server_t server;

    dpls_server_init(&server, &hal, 0u);
    assert(fake.storage_append_count == 0u); /* BOOT must not modify storage. */
    assert(server.event_count == 0u);
    assert(server.next_event_sequence == 1u);
    assert(server.hal.event_storage_append == NULL);
    assert(server.hal.event_storage_read == NULL);

    dpls_server_log(&server, TST_EVT_BLE_CONNECTED, 0u);
    assert(fake.storage_append_count == 0u);
    assert(server.event_count == 0u);
}

static void test_ring_overflow(void) {
    fake_t fake = {.encrypted = true, .initialized = true};
    dpls_hal_t hal = {
        .link_encrypted = encrypted, .hardware_apply_mode = apply, .hardware_safe_normal = normal,
        .voltage_mv = voltage, .power_source = power, .reserve_low = low, .identify_led = identify,
        .random_bytes = random_bytes, .settings_state = settings_state, .settings_salt = salt,
        .settings_write = settings, .verify_auth_proof = verify,
        .event_storage_init = storage_init, .event_storage_append = storage_append,
        .event_storage_read = storage_read,
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

static void test_all_spec_event_types_round_trip(void) {
    fake_t fake = {.encrypted = true, .initialized = true};
    dpls_hal_t hal = {
        .link_encrypted = encrypted, .hardware_apply_mode = apply, .hardware_safe_normal = normal,
        .voltage_mv = voltage, .power_source = power, .reserve_low = low, .identify_led = identify,
        .random_bytes = random_bytes, .settings_state = settings_state, .settings_salt = salt,
        .settings_write = settings, .verify_auth_proof = verify,
        .event_storage_init = storage_init, .event_storage_append = storage_append,
        .event_storage_read = storage_read,
        .tx_indicate = indicate, .tx_notify = notify, .context = &fake,
    };
    dpls_server_t server;
    uint8_t buf[DPLS_MAX_FRAME];
    dpls_event_t exported[16];
    uint16_t count, type;

    dpls_server_init(&server, &hal, 0);
    auth_session(&server, &fake, buf);
    /* init/connect/auth already exercise 1, 2 and 4. Add a deterministic
     * record for every event code from the specification and verify the wire
     * representation preserves both type and parameter. */
    for (type = 1; type <= 11; ++type) {
        server.now_ms = (uint32_t)type * 1000u;
        dpls_server_log(&server, (uint8_t)type, (uint8_t)(0xa0u + type));
    }
    count = export_events(&server, &fake, buf, exported, 16);
    assert(count == 14u);
    for (type = 1; type <= 11; ++type) {
        const dpls_event_t *event = &exported[type + 2u];
        assert(event->event_type == type);
        assert(event->parameter == (uint8_t)(0xa0u + type));
        assert(event->timestamp_seconds == type);
    }
}

static void test_flash_style_storage_survives_reboot_and_streams(void) {
    fake_t fake = {.encrypted = true, .initialized = true};
    dpls_hal_t hal = {
        .link_encrypted = encrypted, .hardware_apply_mode = apply, .hardware_safe_normal = normal,
        .voltage_mv = voltage, .power_source = power, .reserve_low = low, .identify_led = identify,
        .random_bytes = random_bytes, .settings_state = settings_state, .settings_salt = salt,
        .settings_write = settings, .verify_auth_proof = verify,
        .event_storage_init = storage_init, .event_storage_append = storage_append,
        .event_storage_read = storage_read,
        .tx_indicate = indicate, .tx_notify = notify, .context = &fake,
    };
    dpls_server_t first, rebooted;
    dpls_event_t exported[DPLS_EVENT_CAPACITY];
    uint8_t buf[DPLS_MAX_FRAME];
    uint16_t i, count;

    dpls_server_init(&first, &hal, 0);
    for (i = 0; i < 220u; ++i) {
        first.now_ms = (uint32_t)i * 1000u;
        dpls_server_log(&first, TST_EVT_BLE_CONNECTED, (uint8_t)i);
    }
    assert(first.event_count == DPLS_EVENT_CAPACITY);

    /* A fresh server object contains no event array. It reconstructs only
     * count/next-sequence from the same persistent backend. */
    dpls_server_init(&rebooted, &hal, 500000u);
    assert(rebooted.event_count == DPLS_EVENT_CAPACITY);
    assert(rebooted.next_event_sequence == 223u);
    auth_session(&rebooted, &fake, buf);
    fake.storage_read_count = 0;
    count = export_events(&rebooted, &fake, buf, exported, DPLS_EVENT_CAPACITY);
    assert(count == DPLS_EVENT_CAPACITY);
    assert(fake.storage_read_count == DPLS_EVENT_CAPACITY);
    assert(exported[0].sequence == 25u);
    assert(exported[count - 1u].sequence == 224u);
    assert(sizeof(dpls_server_t) < 1024u);
}

int main(void) {
    test_failed_storage_init_disables_journal_for_runtime();
    test_invalid_type_not_stored();
    test_all_spec_event_types_round_trip();
    test_ring_overflow();
    test_flash_style_storage_survives_reboot_and_streams();
    printf("test_log_journal: OK (capacity=%u)\n", (unsigned)DPLS_EVENT_CAPACITY);
    return 0;
}
