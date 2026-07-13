#include "dpls_server.h"
#include <string.h>

void dpls_server_log(dpls_server_t *s, uint8_t type, uint8_t parameter);

enum { EVT_BOOT = 1, EVT_BLE_CONNECTED = 2, EVT_BLE_DISCONNECTED = 3, EVT_AUTH_SUCCESS = 4,
       EVT_AUTH_FAILURE = 5, EVT_AUTH_BLOCKED = 6, EVT_MODE_CHANGED = 7, EVT_MODE_AUTO_RETURN = 8,
       EVT_IDENTIFY_START = 9, EVT_IDENTIFY_STOP = 10, EVT_PASSWORD_SET = 11, EVT_LAST = 11 };

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | ((uint16_t)p[1] << 8)); }
static uint32_t rd32(const uint8_t *p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }
static void wr16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void wr32(uint8_t *p, uint32_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24); }
static bool elapsed(uint32_t now, uint32_t deadline) { return (int32_t)(now - deadline) >= 0; }

static bool send_frame(dpls_server_t *s, uint8_t type, const uint8_t *payload, uint16_t length, bool stream);

static uint16_t log_event_count(const dpls_server_t *s);

static bool event_type_valid(uint8_t type) { return type >= EVT_BOOT && type <= EVT_LAST; }

static bool event_at_export_index(const dpls_server_t *s, uint16_t export_index, dpls_event_t *out) {
    uint32_t sequence;
    if (!s->log_export_active || export_index >= s->log_export_count ||
        !s->hal.event_storage_read) return false;
    sequence = s->log_export_first_sequence + export_index;
    if (!s->hal.event_storage_read(s->hal.context, sequence, out)) return false;
    return out->sequence == sequence && event_type_valid(out->event_type);
}

static void stop_identify(dpls_server_t *s) {
    if (!s->identify_active) return;
    s->identify_active = false;
    s->identify_led_on = false;
    s->hal.identify_led(s->hal.context, false);
}

static void stop_identify_logged(dpls_server_t *s) {
    if (!s->identify_active) return;
    stop_identify(s);
    dpls_server_log(s, EVT_IDENTIFY_STOP, 0);
}

static void start_identify(dpls_server_t *s, uint32_t now_ms) {
    if (s->identify_active) return;
    s->identify_active = true;
    s->identify_deadline_ms = now_ms + DPLS_IDENTIFY_MAX_MS;
    s->identify_blink_last_ms = now_ms;
    s->identify_led_on = true;
    s->hal.identify_led(s->hal.context, true);
    dpls_server_log(s, EVT_IDENTIFY_START, 0);
}

static bool send_frame(dpls_server_t *s, uint8_t type, const uint8_t *payload, uint16_t length, bool stream) {
    size_t encoded_length;
    uint16_t crc;
    if (length > DPLS_MAX_PAYLOAD) return false;
    encoded_length = DPLS_PROTOCOL_OVERHEAD + length;
    s->tx_encoded[0] = DPLS_PROTOCOL_VERSION;
    s->tx_encoded[1] = type;
    s->tx_encoded[2] = 0;
    wr16(s->tx_encoded + 3, ++s->tx_sequence);
    wr16(s->tx_encoded + 5, length);
    if (length) memcpy(s->tx_encoded + 7, payload, length);
    crc = dpls_crc16(s->tx_encoded, encoded_length - 2u);
    wr16(s->tx_encoded + encoded_length - 2u, crc);
    return stream ? s->hal.tx_notify(s->hal.context, s->tx_encoded, encoded_length)
                  : s->hal.tx_indicate(s->hal.context, s->tx_encoded, encoded_length);
}

void dpls_server_log(dpls_server_t *s, uint8_t type, uint8_t parameter) {
    dpls_event_t event;
    if (!event_type_valid(type) || !s->hal.event_storage_append) return;
    memset(&event, 0, sizeof(event));
    event.sequence = s->next_event_sequence;
    event.timestamp_seconds = s->now_ms / 1000u;
    event.event_type = type;
    event.parameter = parameter;
    if (!s->hal.event_storage_append(s->hal.context, &event)) {
        if (s->hal.diagnostic_error) s->hal.diagnostic_error(s->hal.context, false);
        return;
    }
    ++s->next_event_sequence;
    if (s->event_count < DPLS_EVENT_CAPACITY) ++s->event_count;
}

static void force_normal(dpls_server_t *s, dpls_return_reason_t reason) {
    if (s->mode == DPLS_MODE_NORMAL) return;
    s->hal.hardware_safe_normal(s->hal.context);
    s->mode = DPLS_MODE_NORMAL; s->mode_deadline_ms = 0; ++s->state_revision;
    dpls_server_log(s, EVT_MODE_AUTO_RETURN, (uint8_t)reason);
}

void dpls_server_init(dpls_server_t *s, const dpls_hal_t *hal, uint32_t now_ms) {
    memset(s, 0, sizeof(*s));
    s->hal = *hal;
    s->now_ms = now_ms;
    s->mode = DPLS_MODE_NORMAL;
    s->next_event_sequence = 1;
    s->state_revision = 1;
    s->event_count = 0;
    if (s->hal.event_storage_init) {
        uint16_t stored_count = 0;
        uint32_t next_sequence = 1;
        if (s->hal.event_storage_init(s->hal.context, &stored_count, &next_sequence)) {
            s->event_count = stored_count > DPLS_EVENT_CAPACITY ? DPLS_EVENT_CAPACITY : stored_count;
            s->next_event_sequence = next_sequence == 0 ? 1 : next_sequence;
            if (s->event_count >= s->next_event_sequence)
                s->event_count = (uint16_t)(s->next_event_sequence - 1u);
        } else if (s->hal.diagnostic_error) {
            s->hal.diagnostic_error(s->hal.context, false);
        }
    }
    s->hal.hardware_safe_normal(s->hal.context);
    dpls_server_log(s, EVT_BOOT, DPLS_RETURN_BOOT);
}

void dpls_server_connected(dpls_server_t *s, uint32_t now_ms) {
    s->now_ms = now_ms;
    if (s->connected) return;
    s->connected = true; s->authenticated = false; s->hello_received = false;
    s->setup_disconnect_deadline_ms = 0; s->failed_auth_attempts = 0;
    dpls_server_log(s, EVT_BLE_CONNECTED, 0);
}

void dpls_server_disconnected(dpls_server_t *s, uint32_t now_ms) {
    s->now_ms = now_ms;
    if (!s->connected) return;
    s->connected = false; s->authenticated = false;
    s->hello_received = false; s->setup_disconnect_deadline_ms = 0;
    memset(s->session_token, 0, sizeof(s->session_token));
    if (s->identify_active) stop_identify(s);
    force_normal(s, DPLS_RETURN_DISCONNECT);
    dpls_server_log(s, EVT_BLE_DISCONNECTED, 0);
}

void dpls_server_tick(dpls_server_t *s, uint32_t now_ms) {
    s->now_ms = now_ms;
    if (s->mode != DPLS_MODE_NORMAL && s->mode_deadline_ms && elapsed(now_ms, s->mode_deadline_ms))
        force_normal(s, DPLS_RETURN_MODE_TIMEOUT);
    else if (s->mode != DPLS_MODE_NORMAL && (!s->authenticated || elapsed(now_ms, s->last_authenticated_activity_ms + DPLS_SESSION_TIMEOUT_MS)))
        force_normal(s, DPLS_RETURN_SESSION_TIMEOUT);
    else if (s->mode != DPLS_MODE_NORMAL && s->hal.reserve_low(s->hal.context))
        force_normal(s, DPLS_RETURN_LOW_RESERVE);
    if (s->identify_active && elapsed(now_ms, s->identify_deadline_ms)) {
        stop_identify_logged(s);
    } else if (s->identify_active &&
               elapsed(now_ms, s->identify_blink_last_ms + DPLS_IDENTIFY_BLINK_MS)) {
        s->identify_blink_last_ms = now_ms;
        s->identify_led_on = !s->identify_led_on;
        s->hal.identify_led(s->hal.context, s->identify_led_on);
    }
    if (s->setup_disconnect_deadline_ms && elapsed(now_ms, s->setup_disconnect_deadline_ms)) {
        s->setup_disconnect_deadline_ms = 0;
        if (s->hal.disconnect_after_setup) s->hal.disconnect_after_setup(s->hal.context);
    }
}

static bool session_matches(dpls_server_t *s, const dpls_frame_t *f, uint16_t minimum) {
    if (!s->authenticated || f->payload_length < minimum || rd32(f->payload) != s->session_id ||
        memcmp(f->payload + 4, s->session_token, DPLS_SESSION_TOKEN_SIZE) != 0) return false;
    s->last_authenticated_activity_ms = s->now_ms;
    return true;
}

static void send_error(dpls_server_t *s, uint8_t code) { send_frame(s, DPLS_MSG_ERROR, &code, 1, false); }

static bool random_or_fail(dpls_server_t *s, uint8_t *out, size_t length) {
    if (s->hal.random_bytes && s->hal.random_bytes(s->hal.context, out, length)) return true;
    memset(out, 0, length);
    s->authenticated = false;
    s->critical_fault = true;
    force_normal(s, DPLS_RETURN_INTERNAL_ERROR);
    if (s->hal.diagnostic_error) s->hal.diagnostic_error(s->hal.context, true);
    return false;
}

static void send_challenge(dpls_server_t *s) {
    uint8_t payload[37];
    if (!random_or_fail(s, (uint8_t *)&s->session_id, sizeof(s->session_id)) ||
        !random_or_fail(s, s->device_nonce, sizeof(s->device_nonce))) {
        send_error(s, 5);
        return;
    }
    wr32(payload, s->session_id); memcpy(payload + 4, s->device_nonce, 16);
    s->hal.settings_salt(s->hal.context, payload + 20);
    payload[36] = s->hal.settings_state(s->hal.context) == DPLS_SETTINGS_VALID ? 1u : 0u;
    send_frame(s, DPLS_MSG_AUTH_CHALLENGE, payload, sizeof(payload), false);
}

static void send_auth_result(dpls_server_t *s, uint8_t status, uint16_t retry_seconds) {
    uint8_t payload[11]; payload[0] = status; wr16(payload + 1, retry_seconds);
    if (status == 0) {
        if (!random_or_fail(s, s->session_token, sizeof(s->session_token))) {
            /* 3 is reserved for persisted SETUP. Never turn a TRNG failure
             * into a reconnectable/success-looking response. */
            payload[0] = 4;
            memset(payload + 3, 0, 8);
            send_frame(s, DPLS_MSG_AUTH_RESULT, payload, sizeof(payload), false);
            return;
        }
        memcpy(payload + 3, s->session_token, 8);
    }
    else memset(payload + 3, 0, 8);
    send_frame(s, DPLS_MSG_AUTH_RESULT, payload, sizeof(payload), false);
}

static void send_state(dpls_server_t *s) {
    uint8_t p[16]; memset(p, 0, sizeof(p));
    p[0] = (uint8_t)s->mode; p[1] = (uint8_t)s->hal.power_source(s->hal.context);
    wr16(p + 2, s->hal.voltage_mv(s->hal.context));
    if (s->mode != DPLS_MODE_NORMAL && !elapsed(s->now_ms, s->mode_deadline_ms))
        wr16(p + 4, (uint16_t)((s->mode_deadline_ms - s->now_ms + 999u) / 1000u));
    p[6] = s->hal.reserve_low(s->hal.context) ? 1u : 0u; p[7] = s->connected ? 1u : 0u;
    wr32(p + 8, s->now_ms / 1000u); wr32(p + 12, s->state_revision);
    send_frame(s, DPLS_MSG_STATE_REPORT, p, sizeof(p), false);
}

static dpls_cached_command_t *cached(dpls_server_t *s, uint32_t id) {
    uint8_t i; for (i = 0; i < DPLS_COMMAND_CACHE_SIZE; ++i)
        if (s->command_cache[i].valid && s->command_cache[i].session_id == s->session_id && s->command_cache[i].command_id == id) return &s->command_cache[i];
    return 0;
}

static void send_command_result(dpls_server_t *s, const dpls_cached_command_t *c) {
    uint8_t p[8]; wr32(p, c->command_id); p[4] = c->status; p[5] = (uint8_t)c->resulting_mode; wr16(p + 6, c->remaining_seconds);
    send_frame(s, DPLS_MSG_COMMAND_RESULT, p, sizeof(p), false);
}

static void handle_mode(dpls_server_t *s, const dpls_frame_t *f) {
    dpls_cached_command_t result, *old;
    uint32_t id; dpls_mode_t requested, prev_mode;
    if (!session_matches(s, f, 17)) return send_error(s, 2);
    id = rd32(f->payload + 12); requested = (dpls_mode_t)f->payload[16]; prev_mode = s->mode;
    if ((old = cached(s, id)) != 0) return send_command_result(s, old);
    memset(&result, 0, sizeof(result)); result.valid = true; result.session_id = s->session_id; result.command_id = id;
    if (requested > DPLS_MODE_SHORT_T) result.status = 3;
    else {
        s->hal.hardware_safe_normal(s->hal.context);
        if (!s->hal.hardware_apply_mode(s->hal.context, requested)) { s->hal.hardware_safe_normal(s->hal.context); requested = DPLS_MODE_NORMAL; result.status = 4; }
        else {
            s->mode = requested; s->mode_deadline_ms = requested == DPLS_MODE_NORMAL ? 0u : s->now_ms + DPLS_MODE_MAX_MS;
            if (requested != prev_mode) {
                ++s->state_revision;
                dpls_server_log(s, EVT_MODE_CHANGED, (uint8_t)requested);
            }
        }
    }
    result.resulting_mode = s->mode;
    result.remaining_seconds = s->mode == DPLS_MODE_NORMAL ? 0u : (uint16_t)(DPLS_MODE_MAX_MS / 1000u);
    s->command_cache[s->command_cache_cursor] = result;
    s->command_cache_cursor = (uint8_t)((s->command_cache_cursor + 1u) % DPLS_COMMAND_CACHE_SIZE);
    send_command_result(s, &result);
}

static void encode_event(const dpls_event_t *e, uint8_t *p) {
    wr32(p, e->sequence); wr32(p + 4, e->timestamp_seconds); p[8] = e->event_type; p[9] = e->parameter;
}

static uint16_t log_event_count(const dpls_server_t *s) {
    return s->event_count > DPLS_EVENT_CAPACITY ? DPLS_EVENT_CAPACITY : s->event_count;
}

static void clamp_event_count(dpls_server_t *s) {
    if (s->event_count > DPLS_EVENT_CAPACITY) s->event_count = DPLS_EVENT_CAPACITY;
}

static void send_log_chunk_at(dpls_server_t *s, uint16_t export_index) {
    dpls_event_t event;
    if (event_at_export_index(s, export_index, &event)) {
        uint8_t p[12];
        wr16(p, export_index);
        encode_event(&event, p + 2);
        /* Log stream uses indications only — one chunk per LOG_ACK, no notify flood. */
        send_frame(s, DPLS_MSG_LOG_CHUNK, p, sizeof(p), false);
    } else {
        s->log_export_active = false;
        send_error(s, 6);
    }
}

static void send_log_result(dpls_server_t *s) {
    uint8_t ok = 0;
    s->log_export_active = false;
    send_frame(s, DPLS_MSG_LOG_RESULT, &ok, 1, false);
}

static void send_log_from(dpls_server_t *s, uint16_t first) {
    uint16_t count = s->log_export_count;
    if (first < count) send_log_chunk_at(s, first);
    else send_log_result(s);
}

bool dpls_server_receive(dpls_server_t *s, const uint8_t *bytes, size_t length, uint32_t now_ms) {
    dpls_frame_t f; s->now_ms = now_ms;
    if (!dpls_frame_decode(bytes, length, &f)) { send_error(s, 1); return false; }
    if (s->identify_active && f.type != DPLS_MSG_IDENTIFY_START && f.type != DPLS_MSG_IDENTIFY_STOP)
        stop_identify(s);
    switch (f.type) {
    case DPLS_MSG_HELLO:
        if (s->critical_fault || !s->connected || !s->hal.link_encrypted(s->hal.context) || f.payload_length != 16) { send_error(s, 2); break; }
        memcpy(s->client_nonce, f.payload, 16); s->hello_received = true; send_challenge(s); break;
    case DPLS_MSG_SETUP: {
        uint8_t name_len;
        char name[32];
        if (s->critical_fault || !s->connected || !s->hal.link_encrypted(s->hal.context) ||
            s->hal.settings_state(s->hal.context) != DPLS_SETTINGS_EMPTY || !s->hello_received ||
            f.payload_length < 54 || rd32(f.payload) != s->session_id) { send_error(s, 2); break; }
        name_len = f.payload[4];
        if (!name_len || name_len > 31 || f.payload_length != (uint16_t)(5u + name_len + 16u + 32u)) { send_error(s, 3); break; }
        memcpy(name, f.payload + 5, name_len); name[name_len] = '\0';
        if (!s->hal.settings_write(s->hal.context, name, f.payload + 5 + name_len, f.payload + 21 + name_len) ||
            s->hal.settings_state(s->hal.context) != DPLS_SETTINGS_VALID) { send_error(s, 4); break; }
        s->hello_received = false; dpls_server_log(s, EVT_PASSWORD_SET, 0);
        send_auth_result(s, 3, 0); s->setup_disconnect_deadline_ms = now_ms + 500u; break;
    }
    case DPLS_MSG_AUTH_PROOF:
        if (s->critical_fault || !s->connected || !s->hal.link_encrypted(s->hal.context) || !s->hello_received || f.payload_length != 48) { send_error(s, 2); break; }
        if (s->blocked_until_ms && !elapsed(now_ms, s->blocked_until_ms)) { send_auth_result(s, 2, (uint16_t)((s->blocked_until_ms - now_ms + 999u) / 1000u)); break; }
        memcpy(s->client_nonce, f.payload, 16);
        if (s->hal.verify_auth_proof(s->hal.context, s->device_nonce, s->client_nonce, s->session_id, f.payload + 16)) {
            if (!s->authenticated) dpls_server_log(s, EVT_AUTH_SUCCESS, 0);
            s->authenticated = true; s->failed_auth_attempts = 0; s->last_authenticated_activity_ms = now_ms;
            send_auth_result(s, 0, 0);
        } else {
            ++s->failed_auth_attempts; dpls_server_log(s, EVT_AUTH_FAILURE, s->failed_auth_attempts);
            if (s->failed_auth_attempts >= 5) { s->blocked_until_ms = now_ms + DPLS_AUTH_BLOCK_MS; dpls_server_log(s, EVT_AUTH_BLOCKED, 0); send_auth_result(s, 2, 300); }
            else send_auth_result(s, 1, 0);
        }
        break;
    case DPLS_MSG_STATE_GET: if (session_matches(s, &f, 12)) send_state(s); else send_error(s, 2); break;
    case DPLS_MSG_MODE_SET: handle_mode(s, &f); break;
    case DPLS_MSG_KEEP_ALIVE:
        if (f.payload_length == 0) {
            if (!s->connected || !s->hal.link_encrypted(s->hal.context)) send_error(s, 2);
        } else if (!session_matches(s, &f, 12)) {
            send_error(s, 2);
        }
        break;
    case DPLS_MSG_IDENTIFY_START:
        if (!s->connected || !s->hal.link_encrypted(s->hal.context)) { send_error(s, 2); break; }
        start_identify(s, now_ms);
        break;
    case DPLS_MSG_IDENTIFY_STOP:
        stop_identify_logged(s);
        break;
    case DPLS_MSG_LOG_START: {
        uint8_t p[10];
        uint16_t count;
        if (!session_matches(s, &f, 14)) { send_error(s, 2); break; }
        clamp_event_count(s);
        count = log_event_count(s);
        s->log_export_count = count;
        s->log_export_first_sequence = s->next_event_sequence - count;
        s->log_export_active = true;
        wr32(p, s->session_id); wr32(p + 4, (uint32_t)count * 10u); wr16(p + 8, count);
        send_frame(s, DPLS_MSG_LOG_INFO, p, sizeof(p), false);
        break;
    }
    case DPLS_MSG_LOG_ACK: if (session_matches(s, &f, 14)) send_log_from(s, rd16(f.payload + 12)); else send_error(s, 2); break;
    default: send_error(s, 5); break;
    }
    return true;
}
