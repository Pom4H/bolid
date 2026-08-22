#include "dpls_server.h"
#include <string.h>

enum { EVT_BOOT = 1, EVT_BLE_CONNECTED = 2, EVT_BLE_DISCONNECTED = 3, EVT_AUTH_SUCCESS = 4,
       EVT_AUTH_FAILURE = 5, EVT_AUTH_BLOCKED = 6, EVT_MODE_CHANGED = 7, EVT_MODE_AUTO_RETURN = 8,
       EVT_IDENTIFY_START = 9, EVT_IDENTIFY_STOP = 10, EVT_PASSWORD_SET = 11,
       EVT_POWER_CHANGED = 12, EVT_RESERVE_LOW = 13, EVT_AUTO_ISOLATION = 14, EVT_LAST = 14 };

enum {
    DPLS_ERROR_REJECTED = 2,
    DPLS_ERROR_INVALID_VALUE = 3,
    DPLS_ERROR_STORAGE = 4,
    DPLS_ERROR_INTERNAL = 5,
    DPLS_ERROR_UNSUPPORTED = 5,
    DPLS_ERROR_LOG_READ = 6,
    DPLS_ERROR_SETUP_WINDOW_CLOSED = 7,
    DPLS_ERROR_LINK_NOT_SECURE = 8,
    DPLS_ERROR_ALREADY_CONFIGURED = 9,
    DPLS_ERROR_HELLO_REQUIRED = 10,
    DPLS_ERROR_SESSION_MISMATCH = 11,
};

enum {
    DPLS_AUTH_OK = 0,
    DPLS_AUTH_DENIED = 1,
    DPLS_AUTH_BLOCKED = 2,
    DPLS_AUTH_SETUP_COMPLETE = 3,
    DPLS_AUTH_INTERNAL_ERROR = 4,
};

enum {
    DPLS_SETTINGS_OK = 0,
    DPLS_SETTINGS_INVALID_VALUE = 1,
    DPLS_SETTINGS_WRITE_FAILED = 2,
};

enum {
    DPLS_COMMAND_OK = 0,
    DPLS_COMMAND_INVALID_MODE = 3,
    DPLS_COMMAND_APPLY_FAILED = 4,
    DPLS_COMMAND_REAL_SHORT = 5,
    DPLS_COMMAND_LOW_RESERVE = 6,
    DPLS_COMMAND_MEASUREMENTS_UNAVAILABLE = 7,
};

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | ((uint16_t)p[1] << 8)); }
static uint32_t rd32(const uint8_t *p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }
static void wr16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void wr32(uint8_t *p, uint32_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24); }
static bool elapsed(uint32_t now, uint32_t deadline) { return (int32_t)(now - deadline) >= 0; }

static bool send_frame(dpls_server_t *s, uint8_t type, uint8_t flags, uint16_t sequence,
                       const uint8_t *payload, uint16_t length);
static uint16_t log_event_count(const dpls_server_t *s);
static bool event_type_valid(uint8_t type) { return type >= EVT_BOOT && type <= EVT_LAST; }

static void update_now(dpls_server_t *s, uint32_t now_ms) {
    if (s->clock.valid) {
        uint32_t delta_ms = now_ms - s->clock.last_ms;
        uint64_t total_ms = (uint64_t)s->clock.fraction_ms + delta_ms;
        s->clock.unix_seconds += (uint32_t)(total_ms / 1000u);
        s->clock.fraction_ms = (uint16_t)(total_ms % 1000u);
        s->clock.last_ms = now_ms;
    }
    s->now_ms = now_ms;
}

static uint32_t event_timestamp_seconds(const dpls_server_t *s) {
    return s->clock.valid ? s->clock.unix_seconds : 0u;
}

static bool event_at_export_index(const dpls_server_t *s, uint16_t export_index, dpls_event_t *out) {
    uint32_t sequence;
    if (!s->journal.export_active || export_index >= s->journal.export_count || !s->hal.events.read) return false;
    sequence = s->journal.export_first_sequence + export_index;
    if (!s->hal.events.read(s->hal.context, sequence, out)) return false;
    return out->sequence == sequence && event_type_valid(out->event_type);
}

static void stop_identify(dpls_server_t *s) {
    if (!s->identify.active) return;
    s->identify.active = false;
    s->hal.hardware.identify_led(s->hal.context, false);
}

static void stop_identify_logged(dpls_server_t *s) {
    if (!s->identify.active) return;
    stop_identify(s);
    dpls_server_log(s, EVT_IDENTIFY_STOP, 0);
}

static void start_identify(dpls_server_t *s, uint32_t now_ms) {
    if (s->identify.active) return;
    s->identify.active = true;
    s->identify.deadline_ms = now_ms + DPLS_IDENTIFY_MAX_MS;
    s->hal.hardware.identify_led(s->hal.context, true);
    dpls_server_log(s, EVT_IDENTIFY_START, 0);
}

static bool send_frame(dpls_server_t *s, uint8_t type, uint8_t flags, uint16_t sequence,
                       const uint8_t *payload, uint16_t length) {
    size_t encoded_length;
    uint16_t crc;
    if (length > DPLS_MAX_PAYLOAD) return false;
    encoded_length = DPLS_PROTOCOL_OVERHEAD + length;
    s->tx_encoded[0] = DPLS_PROTOCOL_VERSION;
    s->tx_encoded[1] = type;
    s->tx_encoded[2] = flags;
    wr16(s->tx_encoded + 3, sequence);
    wr16(s->tx_encoded + 5, length);
    if (length) memcpy(s->tx_encoded + 7, payload, length);
    crc = dpls_crc16(s->tx_encoded, encoded_length - 2u);
    wr16(s->tx_encoded + encoded_length - 2u, crc);
    return s->hal.link.indicate(s->hal.context, s->tx_encoded, encoded_length);
}

static bool send_response(dpls_server_t *s, uint16_t sequence, uint8_t type,
                          const uint8_t *payload, uint16_t length) {
    return send_frame(s, type, DPLS_FLAG_RESPONSE, sequence, payload, length);
}

static void send_error(dpls_server_t *s, uint16_t sequence, uint8_t code) {
    (void)send_frame(s, DPLS_MSG_ERROR, DPLS_FLAG_RESPONSE | DPLS_FLAG_ERROR,
                     sequence, &code, 1u);
}

static bool append_event(dpls_server_t *s, uint8_t type, uint8_t parameter) {
    dpls_event_t event;
    if (!event_type_valid(type) || !s->hal.events.append) return false;
    memset(&event, 0, sizeof(event));
    event.sequence = s->journal.next_sequence;
    event.timestamp_seconds = event_timestamp_seconds(s);
    event.event_type = type;
    event.parameter = parameter;
    if (!s->hal.events.append(s->hal.context, &event)) return false;
    ++s->journal.next_sequence;
    if (s->journal.count < DPLS_EVENT_CAPACITY) ++s->journal.count;
    return true;
}

void dpls_server_log(dpls_server_t *s, uint8_t type, uint8_t parameter) {
    if (!append_event(s, type, parameter) && s->hal.diagnostic_error)
        s->hal.diagnostic_error(s->hal.context, false);
}

static void force_normal(dpls_server_t *s, dpls_return_reason_t reason) {
    if (s->safety.mode == DPLS_MODE_NORMAL) return;
    s->hal.hardware.safe_normal(s->hal.context);
    dpls_safety_force_normal(&s->safety);
    dpls_server_log(s, EVT_MODE_AUTO_RETURN, (uint8_t)reason);
}

static void enter_critical_fault(dpls_server_t *s) {
    if (s->critical_fault) return;
    s->critical_fault = true;
    s->session.authenticated = false;
    memset(s->session.token, 0, sizeof(s->session.token));
    /* Critical fault bypasses audit logging: the journal itself may be the
     * failed dependency. Hardware is driven to the sink state first. */
    s->hal.hardware.safe_normal(s->hal.context);
    dpls_safety_force_normal(&s->safety);
    if (s->hal.diagnostic_error) s->hal.diagnostic_error(s->hal.context, true);
}

static dpls_return_reason_t safety_return_reason(dpls_safety_return_t reason) {
    switch (reason) {
    case DPLS_SAFETY_RETURN_MODE_TIMEOUT: return DPLS_RETURN_MODE_TIMEOUT;
    case DPLS_SAFETY_RETURN_SESSION_TIMEOUT: return DPLS_RETURN_SESSION_TIMEOUT;
    case DPLS_SAFETY_RETURN_LOW_RESERVE: return DPLS_RETURN_LOW_RESERVE;
    case DPLS_SAFETY_RETURN_DISCONNECT: return DPLS_RETURN_DISCONNECT;
    case DPLS_SAFETY_RETURN_REAL_SHORT: return DPLS_RETURN_AUTO_ISOLATION;
    case DPLS_SAFETY_RETURN_MEASUREMENT_LOST: return DPLS_RETURN_MEASUREMENT_LOST;
    case DPLS_SAFETY_RETURN_NONE: default: return DPLS_RETURN_OPERATOR;
    }
}

void dpls_server_init(dpls_server_t *s, const dpls_hal_t *hal, uint32_t now_ms) {
    memset(s, 0, sizeof(*s));
    s->hal = *hal;
    s->now_ms = now_ms;
    s->boot_ms = now_ms;
    dpls_safety_init(&s->safety);
    s->journal.next_sequence = 1u;
    if (s->hal.events.init) {
        uint16_t stored_count = 0;
        uint32_t next_sequence = 1;
        if (s->hal.events.init(s->hal.context, &stored_count, &next_sequence)) {
            s->journal.count = stored_count > DPLS_EVENT_CAPACITY ? DPLS_EVENT_CAPACITY : stored_count;
            s->journal.next_sequence = next_sequence == 0 ? 1 : next_sequence;
            if (s->journal.count >= s->journal.next_sequence) {
                s->journal.count = (uint16_t)(s->journal.next_sequence - 1u);
            }
        } else if (s->hal.diagnostic_error) s->hal.diagnostic_error(s->hal.context, false);
    }
    if (s->hal.auth.lock_read && s->hal.auth.lock_read(s->hal.context)) {
        s->session.failed_auth_attempts = DPLS_AUTH_MAX_ATTEMPTS;
        s->session.blocked_until_ms = now_ms + DPLS_AUTH_BLOCK_MS;
    }
    s->hal.hardware.safe_normal(s->hal.context);
    dpls_server_log(s, EVT_BOOT, DPLS_RETURN_BOOT);
}

void dpls_server_connected(dpls_server_t *s, uint32_t now_ms) {
    update_now(s, now_ms);
    if (s->session.connected) return;
    s->session.connected = true;
    s->session.authenticated = false;
    s->session.hello_received = false;
    s->session.setup_completed = false;
    s->session.setup_fingerprint = 0u;
    dpls_server_log(s, EVT_BLE_CONNECTED, 0);
}

void dpls_server_disconnected(dpls_server_t *s, uint32_t now_ms) {
    update_now(s, now_ms);
    if (!s->session.connected) return;
    s->session.connected = false;
    s->session.authenticated = false;
    s->session.hello_received = false;
    s->session.setup_completed = false;
    s->session.setup_fingerprint = 0u;
    memset(s->session.token, 0, sizeof(s->session.token));
    if (s->identify.active) stop_identify(s);
    force_normal(s, DPLS_RETURN_DISCONNECT);
    dpls_server_log(s, EVT_BLE_DISCONNECTED, 0);
}

static bool safety_measurements_ready(dpls_server_t *s) {
    const uint8_t required = DPLS_STATE_PORT_1_VALID |
                             DPLS_STATE_RESERVE_VOLTAGE_VALID |
                             DPLS_STATE_AUTOISO_VALID;
    uint8_t validity = s->hal.hardware.measurement_validity ?
        s->hal.hardware.measurement_validity(s->hal.context) : 0u;
    return (validity & required) == required;
}

static dpls_safety_inputs_t sample_safety_inputs(dpls_server_t *s) {
    dpls_safety_inputs_t inputs;
    dpls_power_t source = s->hal.hardware.power_source(s->hal.context);
    bool low = s->hal.hardware.reserve_low(s->hal.context);
    bool real_short = s->hal.hardware.real_short_active ?
        s->hal.hardware.real_short_active(s->hal.context) : false;

    if (!s->observed_inputs.known) {
        s->observed_inputs.known = true;
        s->observed_inputs.power_source = source;
        s->observed_inputs.reserve_low = low;
        s->observed_inputs.real_short_active = real_short;
        if (real_short) dpls_server_log(s, EVT_AUTO_ISOLATION, 1u);
    } else if (source != s->observed_inputs.power_source) {
        s->observed_inputs.power_source = source;
        dpls_server_log(s, EVT_POWER_CHANGED, (uint8_t)source);
    }
    if (low != s->observed_inputs.reserve_low) {
        s->observed_inputs.reserve_low = low;
        dpls_server_log(s, EVT_RESERVE_LOW, low ? 1u : 0u);
    }
    if (real_short != s->observed_inputs.real_short_active) {
        s->observed_inputs.real_short_active = real_short;
        dpls_server_log(s, EVT_AUTO_ISOLATION, real_short ? 1u : 0u);
    }

    inputs.connected = s->session.connected;
    inputs.authenticated = s->session.authenticated;
    inputs.measurements_ready = safety_measurements_ready(s);
    inputs.reserve_low = low;
    inputs.real_short = real_short;
    inputs.last_authenticated_activity_ms = s->session.last_authenticated_activity_ms;
    return inputs;
}

void dpls_server_tick(dpls_server_t *s, uint32_t now_ms) {
    dpls_safety_inputs_t inputs;
    dpls_safety_return_t required;
    update_now(s, now_ms);
    inputs = sample_safety_inputs(s);
    required = dpls_safety_required_return(&s->safety, &inputs, now_ms);
    if (required != DPLS_SAFETY_RETURN_NONE) force_normal(s, safety_return_reason(required));
    if (s->identify.active && elapsed(now_ms, s->identify.deadline_ms)) stop_identify_logged(s);
}

static bool session_matches(dpls_server_t *s, const dpls_frame_t *f, uint16_t minimum) {
    if (!s->session.authenticated || f->payload_length < minimum ||
        rd32(f->payload) != s->session.session_id ||
        memcmp(f->payload + 4, s->session.token, DPLS_SESSION_TOKEN_SIZE) != 0) return false;
    s->session.last_authenticated_activity_ms = s->now_ms;
    return true;
}

static bool random_or_fail(dpls_server_t *s, uint8_t *out, size_t length) {
    if (s->hal.auth.random_bytes && s->hal.auth.random_bytes(s->hal.context, out, length)) return true;
    memset(out, 0, length);
    enter_critical_fault(s);
    return false;
}

static void send_challenge(dpls_server_t *s, uint16_t sequence) {
    uint8_t payload[37];
    if (!random_or_fail(s, (uint8_t *)&s->session.session_id, sizeof(s->session.session_id)) ||
        !random_or_fail(s, s->session.device_nonce, sizeof(s->session.device_nonce))) {
        send_error(s, sequence, DPLS_ERROR_INTERNAL);
        return;
    }
    wr32(payload, s->session.session_id);
    memcpy(payload + 4, s->session.device_nonce, DPLS_AUTH_NONCE_SIZE);
    s->hal.settings.salt(s->hal.context, payload + 20);
    payload[36] = s->hal.settings.state(s->hal.context) == DPLS_SETTINGS_VALID ? 1u : 0u;
    (void)send_response(s, sequence, DPLS_MSG_AUTH_CHALLENGE, payload, sizeof(payload));
}

static void send_auth_result(dpls_server_t *s, uint16_t sequence, uint8_t status, uint16_t retry_seconds) {
    uint8_t payload[11];
    payload[0] = status;
    wr16(payload + 1, retry_seconds);
    if (status == DPLS_AUTH_OK) {
        if (!random_or_fail(s, s->session.token, sizeof(s->session.token))) {
            payload[0] = DPLS_AUTH_INTERNAL_ERROR;
            memset(payload + 3, 0, 8);
            (void)send_response(s, sequence, DPLS_MSG_AUTH_RESULT, payload, sizeof(payload));
            return;
        }
        memcpy(payload + 3, s->session.token, DPLS_SESSION_TOKEN_SIZE);
    } else memset(payload + 3, 0, 8);
    (void)send_response(s, sequence, DPLS_MSG_AUTH_RESULT, payload, sizeof(payload));
}

static void send_state(dpls_server_t *s, uint16_t sequence) {
    uint8_t p[25];
    uint16_t legacy_voltage;
    uint16_t port1_voltage;
    memset(p, 0, sizeof(p));
    legacy_voltage = s->hal.hardware.voltage_mv ? s->hal.hardware.voltage_mv(s->hal.context) : 0u;
    port1_voltage = s->hal.hardware.port1_voltage_mv ?
        s->hal.hardware.port1_voltage_mv(s->hal.context) : legacy_voltage;
    p[0] = (uint8_t)s->safety.mode;
    p[1] = (uint8_t)s->hal.hardware.power_source(s->hal.context);
    wr16(p + 2, legacy_voltage);
    wr16(p + 4, dpls_safety_remaining_seconds(&s->safety, s->now_ms));
    p[6] = s->hal.hardware.reserve_low(s->hal.context) ? 1u : 0u;
    p[7] = (uint8_t)((s->session.connected ? 0x01u : 0u) |
                     (s->observed_inputs.real_short_active ? 0x02u : 0u));
    wr32(p + 8, s->now_ms / 1000u);
    wr32(p + 12, s->safety.revision);
    p[16] = s->hal.hardware.measurement_validity ?
        s->hal.hardware.measurement_validity(s->hal.context) : 0u;
    wr16(p + 17, port1_voltage);
    wr16(p + 19, s->hal.hardware.port2_voltage_mv ? s->hal.hardware.port2_voltage_mv(s->hal.context) : 0u);
    wr16(p + 21, s->hal.hardware.port_t_voltage_mv ? s->hal.hardware.port_t_voltage_mv(s->hal.context) : 0u);
    wr16(p + 23, s->hal.hardware.reserve_voltage_mv ? s->hal.hardware.reserve_voltage_mv(s->hal.context) : 0u);
    (void)send_response(s, sequence, DPLS_MSG_STATE_REPORT, p, sizeof(p));
}

static void send_device_info(dpls_server_t *s, uint16_t sequence) {
    uint8_t p[12u + DPLS_NAME_MAX + 1u];
    dpls_device_info_t info;
    char name[DPLS_NAME_MAX + 1u];
    uint8_t name_len = 0;
    memset(&info, 0, sizeof(info));
    if (s->hal.hardware.device_info) s->hal.hardware.device_info(s->hal.context, &info);
    name[0] = '\0';
    if (s->hal.settings.name) s->hal.settings.name(s->hal.context, name);
    while (name_len < DPLS_NAME_MAX && name[name_len]) ++name_len;
    wr32(p, info.device_id);
    p[4] = DPLS_PROTOCOL_VERSION;
    p[5] = info.fw_major;
    p[6] = info.fw_minor;
    p[7] = info.fw_patch;
    p[8] = info.hw_revision;
    p[9] = info.capabilities;
    p[10] = (uint8_t)s->hal.settings.state(s->hal.context);
    p[11] = name_len;
    if (name_len) memcpy(p + 12, name, name_len);
    (void)send_response(s, sequence, DPLS_MSG_DEVICE_INFO_REPORT, p, (uint16_t)(12u + name_len));
}

static void send_settings_result(dpls_server_t *s, uint16_t sequence, uint8_t status) {
    (void)send_response(s, sequence, DPLS_MSG_SETTINGS_RESULT, &status, 1u);
}

static dpls_cached_command_t *cached(dpls_server_t *s, uint16_t sequence) {
    uint8_t i;
    for (i = 0; i < DPLS_COMMAND_CACHE_SIZE; ++i)
        if (s->command_cache.entries[i].valid &&
            s->command_cache.entries[i].session_id == s->session.session_id &&
            s->command_cache.entries[i].request_sequence == sequence) {
            return &s->command_cache.entries[i];
        }
    return 0;
}

static void send_command_result(dpls_server_t *s, uint16_t sequence, const dpls_cached_command_t *c) {
    uint8_t p[4];
    p[0] = c->status;
    p[1] = (uint8_t)c->resulting_mode;
    wr16(p + 2, c->remaining_seconds);
    (void)send_response(s, sequence, DPLS_MSG_COMMAND_RESULT, p, sizeof(p));
}

static uint8_t command_status_for_safety(dpls_safety_return_t reason) {
    switch (reason) {
    case DPLS_SAFETY_RETURN_REAL_SHORT: return DPLS_COMMAND_REAL_SHORT;
    case DPLS_SAFETY_RETURN_LOW_RESERVE: return DPLS_COMMAND_LOW_RESERVE;
    case DPLS_SAFETY_RETURN_MEASUREMENT_LOST: return DPLS_COMMAND_MEASUREMENTS_UNAVAILABLE;
    case DPLS_SAFETY_RETURN_NONE: return DPLS_COMMAND_OK;
    default: return DPLS_ERROR_REJECTED;
    }
}

static void handle_mode(dpls_server_t *s, const dpls_frame_t *f) {
    dpls_cached_command_t result, *old;
    dpls_safety_inputs_t inputs;
    dpls_safety_return_t admission;
    dpls_mode_t requested, prev_mode;
    if (!session_matches(s, f, 13u) || f->payload_length != 13u) {
        send_error(s, f->sequence, DPLS_ERROR_REJECTED);
        return;
    }
    requested = (dpls_mode_t)f->payload[12];
    prev_mode = s->safety.mode;
    if ((old = cached(s, f->sequence)) != 0) return send_command_result(s, f->sequence, old);
    memset(&result, 0, sizeof(result));
    result.valid = true;
    result.session_id = s->session.session_id;
    result.request_sequence = f->sequence;
    result.status = DPLS_COMMAND_OK;
    if (requested > DPLS_MODE_SHORT_T) {
        result.status = DPLS_COMMAND_INVALID_MODE;
    } else {
        inputs = sample_safety_inputs(s);
        admission = dpls_safety_admission_reason(requested, &inputs, s->now_ms);
        if (admission != DPLS_SAFETY_RETURN_NONE) {
            result.status = command_status_for_safety(admission);
        } else {
            s->hal.hardware.safe_normal(s->hal.context);
            if (!s->hal.hardware.apply_mode(s->hal.context, requested)) {
                s->hal.hardware.safe_normal(s->hal.context);
                dpls_safety_force_normal(&s->safety);
                result.status = DPLS_COMMAND_APPLY_FAILED;
            } else {
                dpls_safety_commit_mode(&s->safety, requested, s->now_ms);
                if (requested != prev_mode && !append_event(s, EVT_MODE_CHANGED, (uint8_t)requested)) {
                    /* Dangerous mode without an audit record is not an admissible
                     * steady state. Roll back immediately and close the session. */
                    enter_critical_fault(s);
                    result.status = DPLS_COMMAND_APPLY_FAILED;
                }
            }
        }
    }
    result.resulting_mode = s->safety.mode;
    result.remaining_seconds = dpls_safety_remaining_seconds(&s->safety, s->now_ms);
    s->command_cache.entries[s->command_cache.cursor] = result;
    s->command_cache.cursor = (uint8_t)((s->command_cache.cursor + 1u) % DPLS_COMMAND_CACHE_SIZE);
    send_command_result(s, f->sequence, &result);
}

static void encode_event(const dpls_event_t *e, uint8_t *p) {
    wr32(p, e->sequence);
    wr32(p + 4, e->timestamp_seconds);
    p[8] = e->event_type;
    p[9] = e->parameter;
}

static uint16_t log_event_count(const dpls_server_t *s) {
    return s->journal.count > DPLS_EVENT_CAPACITY ? DPLS_EVENT_CAPACITY : s->journal.count;
}

static void clamp_event_count(dpls_server_t *s) {
    if (s->journal.count > DPLS_EVENT_CAPACITY) s->journal.count = DPLS_EVENT_CAPACITY;
}

static void send_log_chunk_at(dpls_server_t *s, uint16_t sequence, uint16_t first_index) {
    uint8_t p[3u + DPLS_LOG_CHUNK_EVENTS * 10u];
    dpls_event_t event;
    uint8_t n = 0;
    while (n < DPLS_LOG_CHUNK_EVENTS &&
           (uint16_t)(first_index + n) < s->journal.export_count &&
           event_at_export_index(s, (uint16_t)(first_index + n), &event)) {
        encode_event(&event, p + 3u + (uint16_t)n * 10u);
        ++n;
    }
    if (n != 0u) {
        wr16(p, first_index);
        p[2] = n;
        (void)send_response(s, sequence, DPLS_MSG_LOG_CHUNK, p, (uint16_t)(3u + (uint16_t)n * 10u));
    } else {
        s->journal.export_active = false;
        send_error(s, sequence, DPLS_ERROR_LOG_READ);
    }
}

static void send_log_result(dpls_server_t *s, uint16_t sequence) {
    uint8_t ok = 0;
    s->journal.export_active = false;
    (void)send_response(s, sequence, DPLS_MSG_LOG_RESULT, &ok, 1u);
}

static void send_log_from(dpls_server_t *s, uint16_t sequence, uint16_t first) {
    if (first < s->journal.export_count) send_log_chunk_at(s, sequence, first);
    else send_log_result(s, sequence);
}

static bool auth_block_active(dpls_server_t *s, uint32_t now) {
    if (!s->session.blocked_until_ms) return false;
    if (!elapsed(now, s->session.blocked_until_ms)) return true;

    /* Сначала stage durable unlock. Только после успешного stage RAM lockout
     * считается снятым. Иначе reboot мог бы вернуть противоречивое состояние. */
    if (!s->hal.auth.lock_write || !s->hal.auth.lock_write(s->hal.context, false)) {
        enter_critical_fault(s);
        return true;
    }
    s->session.blocked_until_ms = 0;
    s->session.failed_auth_attempts = 0;
    return false;
}

static bool secure_link_ready(const dpls_server_t *s) {
    return !s->critical_fault && s->session.connected && s->hal.link.encrypted &&
           s->hal.link.encrypted(s->hal.context);
}

static uint32_t setup_payload_fingerprint(const uint8_t *payload, uint16_t length) {
    uint32_t hash = 2166136261u;
    uint16_t i;
    for (i = 0u; i < length; ++i) {
        hash ^= payload[i];
        hash *= 16777619u;
    }
    return hash;
}

static void handle_hello(dpls_server_t *s, const dpls_frame_t *f) {
    if (!secure_link_ready(s)) {
        send_error(s, f->sequence, DPLS_ERROR_LINK_NOT_SECURE);
        return;
    }
    if (f->payload_length != DPLS_AUTH_NONCE_SIZE) {
        send_error(s, f->sequence, DPLS_ERROR_INVALID_VALUE);
        return;
    }
    memcpy(s->session.client_nonce, f->payload, DPLS_AUTH_NONCE_SIZE);
    s->session.hello_received = true;
    send_challenge(s, f->sequence);
}

static void handle_setup(dpls_server_t *s, const dpls_frame_t *f) {
    char name[DPLS_NAME_MAX + 1u];
    dpls_settings_state_t settings_state;
    uint8_t name_length;
    uint16_t expected_length;
    uint32_t fingerprint;

    if (!secure_link_ready(s)) {
        send_error(s, f->sequence, DPLS_ERROR_LINK_NOT_SECURE);
        return;
    }
    if (f->payload_length < 54u) {
        send_error(s, f->sequence, DPLS_ERROR_INVALID_VALUE);
        return;
    }
    if (rd32(f->payload) != s->session.session_id) {
        send_error(s, f->sequence, DPLS_ERROR_SESSION_MISMATCH);
        return;
    }

    fingerprint = setup_payload_fingerprint(f->payload, f->payload_length);
    if (s->session.setup_completed) {
        if (fingerprint == s->session.setup_fingerprint) {
            /* The first SETUP may already have changed settings_state to VALID
             * while a duplicate request was sitting in the RX queue. Retrying
             * the exact transaction is success, never a false "already set". */
            send_auth_result(s, f->sequence, DPLS_AUTH_SETUP_COMPLETE, 0);
        } else {
            send_error(s, f->sequence, DPLS_ERROR_ALREADY_CONFIGURED);
        }
        return;
    }

    settings_state = s->hal.settings.state(s->hal.context);
    if (settings_state == DPLS_SETTINGS_VALID) {
        send_error(s, f->sequence, DPLS_ERROR_ALREADY_CONFIGURED);
        return;
    }
    if (settings_state == DPLS_SETTINGS_CORRUPT) {
        send_error(s, f->sequence, DPLS_ERROR_STORAGE);
        return;
    }
    if (!s->session.hello_received) {
        send_error(s, f->sequence, DPLS_ERROR_HELLO_REQUIRED);
        return;
    }
    if (elapsed(s->now_ms, s->boot_ms + DPLS_SETUP_WINDOW_MS)) {
        send_error(s, f->sequence, DPLS_ERROR_SETUP_WINDOW_CLOSED);
        return;
    }
    name_length = f->payload[4];
    expected_length = (uint16_t)(5u + name_length + DPLS_AUTH_SALT_SIZE + DPLS_AUTH_PROOF_SIZE);
    if (name_length == 0u || name_length > DPLS_NAME_MAX || f->payload_length != expected_length) {
        send_error(s, f->sequence, DPLS_ERROR_INVALID_VALUE);
        return;
    }
    memcpy(name, f->payload + 5, name_length);
    name[name_length] = '\0';
    if (!s->hal.settings.write(s->hal.context, name, f->payload + 5 + name_length,
                               f->payload + 5 + name_length + DPLS_AUTH_SALT_SIZE) ||
        s->hal.settings.state(s->hal.context) != DPLS_SETTINGS_VALID) {
        send_error(s, f->sequence, DPLS_ERROR_STORAGE);
        return;
    }
    s->session.setup_completed = true;
    s->session.setup_fingerprint = fingerprint;
    s->session.hello_received = false;
    send_auth_result(s, f->sequence, DPLS_AUTH_SETUP_COMPLETE, 0);
    dpls_server_log(s, EVT_PASSWORD_SET, 0);
}

static void block_authentication(dpls_server_t *s, const dpls_frame_t *f) {
    s->session.authenticated = false;
    s->session.blocked_until_ms = s->now_ms + DPLS_AUTH_BLOCK_MS;

    /* A lockout that cannot be staged for persistence is not a lockout. Fail
     * closed instead of reporting BLOCKED while reboot could bypass it. */
    if (!s->hal.auth.lock_write || !s->hal.auth.lock_write(s->hal.context, true)) {
        enter_critical_fault(s);
        send_auth_result(s, f->sequence, DPLS_AUTH_INTERNAL_ERROR, 0);
        return;
    }

    send_auth_result(s, f->sequence, DPLS_AUTH_BLOCKED,
                     (uint16_t)(DPLS_AUTH_BLOCK_MS / 1000u));
    dpls_server_log(s, EVT_AUTH_BLOCKED, 0);
}

static void handle_auth_proof(dpls_server_t *s, const dpls_frame_t *f) {
    if (!secure_link_ready(s)) {
        send_error(s, f->sequence, DPLS_ERROR_LINK_NOT_SECURE);
        return;
    }
    if (!s->session.hello_received) {
        send_error(s, f->sequence, DPLS_ERROR_HELLO_REQUIRED);
        return;
    }
    if (f->payload_length != DPLS_AUTH_NONCE_SIZE + DPLS_AUTH_PROOF_SIZE) {
        send_error(s, f->sequence, DPLS_ERROR_INVALID_VALUE);
        return;
    }
    if (auth_block_active(s, s->now_ms)) {
        if (s->critical_fault) {
            send_auth_result(s, f->sequence, DPLS_AUTH_INTERNAL_ERROR, 0);
        } else {
            uint16_t retry_seconds =
                (uint16_t)((s->session.blocked_until_ms - s->now_ms + 999u) / 1000u);
            send_auth_result(s, f->sequence, DPLS_AUTH_BLOCKED, retry_seconds);
        }
        return;
    }
    if (s->session.last_auth_proof_ms &&
        (uint32_t)(s->now_ms - s->session.last_auth_proof_ms) < DPLS_AUTH_MIN_INTERVAL_MS) {
        send_auth_result(s, f->sequence, DPLS_AUTH_DENIED, 0);
        return;
    }
    s->session.last_auth_proof_ms = s->now_ms;
    memcpy(s->session.client_nonce, f->payload, DPLS_AUTH_NONCE_SIZE);
    if (s->hal.auth.verify_proof(s->hal.context, s->session.device_nonce,
                                 s->session.client_nonce, s->session.session_id,
                                 f->payload + DPLS_AUTH_NONCE_SIZE)) {
        bool first_success = !s->session.authenticated;

        /* AUTH_OK is published only after the durable lock state accepted an
         * unlock stage. Production storage may commit it only after link drain,
         * but a failed stage can never be hidden behind a successful session. */
        if (!s->hal.auth.lock_write || !s->hal.auth.lock_write(s->hal.context, false)) {
            enter_critical_fault(s);
            send_auth_result(s, f->sequence, DPLS_AUTH_INTERNAL_ERROR, 0);
            return;
        }

        s->session.authenticated = true;
        s->session.failed_auth_attempts = 0;
        s->session.last_authenticated_activity_ms = s->now_ms;
        send_auth_result(s, f->sequence, DPLS_AUTH_OK, 0);
        if (s->critical_fault) return;
        if (first_success) dpls_server_log(s, EVT_AUTH_SUCCESS, 0);
        return;
    }
    ++s->session.failed_auth_attempts;
    if (s->session.failed_auth_attempts >= DPLS_AUTH_MAX_ATTEMPTS) {
        block_authentication(s, f);
    } else {
        send_auth_result(s, f->sequence, DPLS_AUTH_DENIED, 0);
        dpls_server_log(s, EVT_AUTH_FAILURE, s->session.failed_auth_attempts);
    }
}

static void handle_device_info_get(dpls_server_t *s, const dpls_frame_t *f) {
    if (session_matches(s, f, 12u) && f->payload_length == 12u) {
        send_device_info(s, f->sequence);
    } else {
        send_error(s, f->sequence, DPLS_ERROR_REJECTED);
    }
}

static void handle_name_set(dpls_server_t *s, const dpls_frame_t *f) {
    char name[DPLS_NAME_MAX + 1u];
    uint8_t name_length;

    if (!session_matches(s, f, 13u)) {
        send_error(s, f->sequence, DPLS_ERROR_REJECTED);
        return;
    }
    name_length = f->payload[12];
    if (name_length == 0u || name_length > DPLS_NAME_MAX ||
        f->payload_length != (uint16_t)(13u + name_length)) {
        send_settings_result(s, f->sequence, DPLS_SETTINGS_INVALID_VALUE);
        return;
    }
    memcpy(name, f->payload + 13, name_length);
    name[name_length] = '\0';
    if (!s->hal.settings.set_name || !s->hal.settings.set_name(s->hal.context, name)) {
        send_settings_result(s, f->sequence, DPLS_SETTINGS_WRITE_FAILED);
        return;
    }
    send_settings_result(s, f->sequence, DPLS_SETTINGS_OK);
}

static void handle_password_set(dpls_server_t *s, const dpls_frame_t *f) {
    if (!session_matches(s, f, 60u) || f->payload_length != 60u) {
        send_error(s, f->sequence, DPLS_ERROR_REJECTED);
        return;
    }
    if (!s->hal.settings.set_password ||
        !s->hal.settings.set_password(s->hal.context, f->payload + 12, f->payload + 28)) {
        send_settings_result(s, f->sequence, DPLS_SETTINGS_WRITE_FAILED);
        return;
    }
    send_settings_result(s, f->sequence, DPLS_SETTINGS_OK);
    dpls_server_log(s, EVT_PASSWORD_SET, 0);
    s->session.authenticated = false;
    memset(s->session.token, 0, sizeof(s->session.token));
}

static void handle_time_sync(dpls_server_t *s, const dpls_frame_t *f) {
    uint32_t unix_seconds;
    if (!session_matches(s, f, 16u) || f->payload_length != 16u) {
        send_error(s, f->sequence, DPLS_ERROR_REJECTED);
        return;
    }
    unix_seconds = rd32(f->payload + 12);
    if (unix_seconds < DPLS_TIME_MIN_UNIX_SECONDS || unix_seconds > DPLS_TIME_MAX_UNIX_SECONDS) {
        send_error(s, f->sequence, DPLS_ERROR_INVALID_VALUE);
        return;
    }
    s->clock.valid = true;
    s->clock.unix_seconds = unix_seconds;
    s->clock.last_ms = s->now_ms;
    s->clock.fraction_ms = 0u;
    (void)send_response(s, f->sequence, DPLS_MSG_TIME_SYNC, 0, 0u);
}

static void handle_state_get(dpls_server_t *s, const dpls_frame_t *f) {
    if (session_matches(s, f, 12u) && f->payload_length == 12u) {
        send_state(s, f->sequence);
    } else {
        send_error(s, f->sequence, DPLS_ERROR_REJECTED);
    }
}

static void handle_keep_alive(dpls_server_t *s, const dpls_frame_t *f) {
    if (f->payload_length == 0u) {
        if (!s->session.connected || !s->hal.link.encrypted || !s->hal.link.encrypted(s->hal.context)) {
            send_error(s, f->sequence, DPLS_ERROR_REJECTED);
        }
        return;
    }
    if (!session_matches(s, f, 12u) || f->payload_length != 12u) {
        send_error(s, f->sequence, DPLS_ERROR_REJECTED);
    } else if ((f->flags & DPLS_FLAG_REQUEST) != 0u) {
        (void)send_response(s, f->sequence, DPLS_MSG_KEEP_ALIVE, 0, 0u);
    }
}

static void handle_identify_start(dpls_server_t *s, const dpls_frame_t *f) {
    if (!s->session.connected || !s->hal.link.encrypted ||
        !s->hal.link.encrypted(s->hal.context) || f->payload_length != 0u) {
        send_error(s, f->sequence, DPLS_ERROR_REJECTED);
        return;
    }
    start_identify(s, s->now_ms);
    (void)send_response(s, f->sequence, DPLS_MSG_IDENTIFY_START, 0, 0u);
}

static void handle_identify_stop(dpls_server_t *s, const dpls_frame_t *f) {
    stop_identify_logged(s);
    (void)send_response(s, f->sequence, DPLS_MSG_IDENTIFY_STOP, 0, 0u);
}

static void handle_log_start(dpls_server_t *s, const dpls_frame_t *f) {
    uint8_t payload[10];
    uint16_t count;
    if (!session_matches(s, f, 12u) || f->payload_length != 12u) {
        send_error(s, f->sequence, DPLS_ERROR_REJECTED);
        return;
    }
    clamp_event_count(s);
    count = log_event_count(s);
    s->journal.export_count = count;
    s->journal.export_first_sequence = s->journal.next_sequence - count;
    s->journal.export_active = true;
    wr32(payload, s->session.session_id);
    wr32(payload + 4, (uint32_t)count * 10u);
    wr16(payload + 8, count);
    (void)send_response(s, f->sequence, DPLS_MSG_LOG_INFO, payload, sizeof(payload));
}

static void handle_log_ack(dpls_server_t *s, const dpls_frame_t *f) {
    if (session_matches(s, f, 14u) && f->payload_length == 14u) {
        send_log_from(s, f->sequence, rd16(f->payload + 12));
    } else {
        send_error(s, f->sequence, DPLS_ERROR_REJECTED);
    }
}

static void dispatch_request(dpls_server_t *s, const dpls_frame_t *f) {
    switch (f->type) {
    case DPLS_MSG_HELLO: handle_hello(s, f); break;
    case DPLS_MSG_SETUP: handle_setup(s, f); break;
    case DPLS_MSG_AUTH_PROOF: handle_auth_proof(s, f); break;
    case DPLS_MSG_DEVICE_INFO_GET: handle_device_info_get(s, f); break;
    case DPLS_MSG_NAME_SET: handle_name_set(s, f); break;
    case DPLS_MSG_PASSWORD_SET: handle_password_set(s, f); break;
    case DPLS_MSG_TIME_SYNC: handle_time_sync(s, f); break;
    case DPLS_MSG_STATE_GET: handle_state_get(s, f); break;
    case DPLS_MSG_MODE_SET: handle_mode(s, f); break;
    case DPLS_MSG_KEEP_ALIVE: handle_keep_alive(s, f); break;
    case DPLS_MSG_IDENTIFY_START: handle_identify_start(s, f); break;
    case DPLS_MSG_IDENTIFY_STOP: handle_identify_stop(s, f); break;
    case DPLS_MSG_LOG_START: handle_log_start(s, f); break;
    case DPLS_MSG_LOG_ACK: handle_log_ack(s, f); break;
    default: send_error(s, f->sequence, DPLS_ERROR_UNSUPPORTED); break;
    }
}

bool dpls_server_receive(dpls_server_t *s, const uint8_t *bytes, size_t length, uint32_t now_ms) {
    dpls_frame_t frame;
    update_now(s, now_ms);
    if (!dpls_frame_decode(bytes, length, &frame)) return false;
    if (frame.type != DPLS_MSG_KEEP_ALIVE && (frame.flags & DPLS_FLAG_REQUEST) == 0u) {
        send_error(s, frame.sequence, DPLS_ERROR_REJECTED);
        return false;
    }
    if (s->identify.active && frame.type != DPLS_MSG_IDENTIFY_START &&
        frame.type != DPLS_MSG_IDENTIFY_STOP) {
        stop_identify(s);
    }
    dispatch_request(s, &frame);
    return true;
}

bool dpls_server_authenticated(const dpls_server_t *s) {
    return s->session.authenticated;
}