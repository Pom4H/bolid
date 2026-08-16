#ifndef DPLS_SERVER_H
#define DPLS_SERVER_H

#include "dpls_protocol.h"
#include "dpls_safety.h"

#define DPLS_AUTH_NONCE_SIZE 16u
#define DPLS_AUTH_SALT_SIZE 16u
#define DPLS_AUTH_PROOF_SIZE 32u
#define DPLS_SESSION_TOKEN_SIZE 8u
#define DPLS_COMMAND_CACHE_SIZE 8u
#define DPLS_NAME_MAX 31u
#define DPLS_FW_VERSION_MAJOR 2u
#define DPLS_FW_VERSION_MINOR 0u
#define DPLS_FW_VERSION_PATCH 0u
#define DPLS_TIME_MIN_UNIX_SECONDS 1577836800u
#define DPLS_TIME_MAX_UNIX_SECONDS 4102444799u

enum {
    DPLS_CAP_ADC_PRESENT           = 1u << 0,
    DPLS_CAP_HW_READBACK           = 1u << 1,
    DPLS_CAP_ADC_CALIBRATED        = 1u << 2,
    DPLS_CAP_MULTI_VOLTAGE_REPORT  = 1u << 3,
};

#ifndef DPLS_EVENT_CAPACITY
#define DPLS_EVENT_CAPACITY 200u
#endif
#define DPLS_LOG_CHUNK_EVENTS 15u
#define DPLS_MODE_MAX_MS DPLS_SAFETY_MODE_MAX_MS
#define DPLS_AUTH_BLOCK_MS 300000u
#define DPLS_AUTH_MAX_ATTEMPTS 5u
#define DPLS_AUTH_MIN_INTERVAL_MS 1000u
#define DPLS_SETUP_WINDOW_MS 300000u
#define DPLS_IDENTIFY_MAX_MS 60000u

typedef dpls_safety_mode_t dpls_mode_t;
#define DPLS_MODE_NORMAL DPLS_SAFE_NORMAL
#define DPLS_MODE_OPEN_T DPLS_SAFE_OPEN_T
#define DPLS_MODE_OPEN_MAIN DPLS_SAFE_OPEN_MAIN
#define DPLS_MODE_SHORT_1 DPLS_SAFE_SHORT_1
#define DPLS_MODE_SHORT_2 DPLS_SAFE_SHORT_2
#define DPLS_MODE_SHORT_T DPLS_SAFE_SHORT_T

typedef enum { DPLS_POWER_LINE = 0, DPLS_POWER_RESERVE = 1 } dpls_power_t;

enum {
    DPLS_STATE_LINE_VOLTAGE_VALID = 1u << 0,
    DPLS_STATE_RESERVE_VALID      = 1u << 1,
    DPLS_STATE_POWER_VALID        = 1u << 2,
    DPLS_STATE_AUTOISO_VALID      = 1u << 3,
    DPLS_STATE_ADC_CALIBRATED     = 1u << 4,
    DPLS_STATE_PORT_2_VALID       = 1u << 5,
    DPLS_STATE_PORT_T_VALID       = 1u << 6,
};
#define DPLS_STATE_PORT_1_VALID DPLS_STATE_LINE_VOLTAGE_VALID
#define DPLS_STATE_RESERVE_VOLTAGE_VALID DPLS_STATE_RESERVE_VALID

typedef enum {
    DPLS_SETTINGS_EMPTY = 0,
    DPLS_SETTINGS_VALID = 1,
    DPLS_SETTINGS_CORRUPT = 2,
} dpls_settings_state_t;

typedef enum {
    DPLS_RETURN_OPERATOR = 0,
    DPLS_RETURN_MODE_TIMEOUT = 1,
    DPLS_RETURN_SESSION_TIMEOUT = 2,
    DPLS_RETURN_DISCONNECT = 3,
    DPLS_RETURN_LOW_RESERVE = 4,
    DPLS_RETURN_INTERNAL_ERROR = 5,
    DPLS_RETURN_BOOT = 6,
    DPLS_RETURN_AUTO_ISOLATION = 7
} dpls_return_reason_t;

typedef struct {
    uint32_t sequence;
    uint32_t timestamp_seconds;
    uint8_t event_type;
    uint8_t parameter;
} dpls_event_t;

typedef struct {
    uint32_t device_id;
    uint8_t fw_major;
    uint8_t fw_minor;
    uint8_t fw_patch;
    uint8_t hw_revision;
    uint8_t capabilities;
} dpls_device_info_t;

typedef struct {
    bool (*encrypted)(void *context);
    bool (*indicate)(void *context, const uint8_t *frame, size_t length);
    void (*disconnect)(void *context);
} dpls_link_hal_t;

typedef struct {
    bool (*apply_mode)(void *context, dpls_mode_t mode);
    void (*safe_normal)(void *context);
    uint16_t (*voltage_mv)(void *context);
    uint16_t (*port1_voltage_mv)(void *context);
    uint16_t (*port2_voltage_mv)(void *context);
    uint16_t (*port_t_voltage_mv)(void *context);
    uint16_t (*reserve_voltage_mv)(void *context);
    dpls_power_t (*power_source)(void *context);
    bool (*reserve_low)(void *context);
    uint8_t (*measurement_validity)(void *context);
    void (*identify_led)(void *context, bool enabled);
    bool (*real_short_active)(void *context);
    void (*device_info)(void *context, dpls_device_info_t *out);
} dpls_hardware_hal_t;

typedef struct {
    dpls_settings_state_t (*state)(void *context);
    void (*salt)(void *context, uint8_t out[DPLS_AUTH_SALT_SIZE]);
    bool (*write)(void *context, const char *name, const uint8_t salt[16], const uint8_t verifier[32]);
    void (*name)(void *context, char out[DPLS_NAME_MAX + 1u]);
    bool (*set_name)(void *context, const char *name);
    bool (*set_password)(void *context, const uint8_t salt[16], const uint8_t verifier[32]);
} dpls_settings_hal_t;

typedef struct {
    bool (*random_bytes)(void *context, uint8_t *out, size_t length);
    bool (*verify_proof)(void *context, const uint8_t device_nonce[16], const uint8_t client_nonce[16], uint32_t session_id, const uint8_t proof[32]);
    bool (*lock_read)(void *context);
    bool (*lock_write)(void *context, bool locked);
} dpls_auth_hal_t;

typedef struct {
    bool (*init)(void *context, uint16_t *count, uint32_t *next_sequence);
    bool (*append)(void *context, const dpls_event_t *event);
    bool (*read)(void *context, uint32_t sequence, dpls_event_t *event);
} dpls_event_store_hal_t;

typedef struct {
    dpls_link_hal_t link;
    dpls_hardware_hal_t hardware;
    dpls_settings_hal_t settings;
    dpls_auth_hal_t auth;
    dpls_event_store_hal_t events;
    void *context;
    void (*diagnostic_error)(void *context, bool critical);
} dpls_hal_t;

typedef struct {
    bool valid;
    uint32_t session_id;
    uint16_t request_sequence;
    uint8_t status;
    dpls_mode_t resulting_mode;
    uint16_t remaining_seconds;
} dpls_cached_command_t;

typedef struct {
    bool connected;
    bool authenticated;
    bool hello_received;
    uint8_t failed_auth_attempts;
    uint32_t blocked_until_ms;
    uint32_t last_auth_proof_ms;
    uint32_t session_id;
    uint32_t last_authenticated_activity_ms;
    uint32_t setup_disconnect_deadline_ms;
    uint8_t device_nonce[DPLS_AUTH_NONCE_SIZE];
    uint8_t client_nonce[DPLS_AUTH_NONCE_SIZE];
    uint8_t token[DPLS_SESSION_TOKEN_SIZE];
} dpls_server_session_t;

typedef struct {
    bool valid;
    uint32_t unix_seconds;
    uint32_t last_ms;
    uint16_t fraction_ms;
} dpls_server_clock_t;

typedef struct {
    bool active;
    uint32_t deadline_ms;
} dpls_server_identify_t;

typedef struct {
    bool known;
    dpls_power_t power_source;
    bool reserve_low;
    bool real_short_active;
} dpls_server_observed_inputs_t;

typedef struct {
    dpls_cached_command_t entries[DPLS_COMMAND_CACHE_SIZE];
    uint8_t cursor;
} dpls_server_command_cache_t;

typedef struct {
    uint16_t count;
    uint32_t next_sequence;
    bool export_active;
    uint16_t export_count;
    uint32_t export_first_sequence;
} dpls_server_journal_t;

typedef struct {
    dpls_hal_t hal;
    dpls_safety_t safety;
    dpls_server_session_t session;
    dpls_server_clock_t clock;
    dpls_server_identify_t identify;
    dpls_server_observed_inputs_t observed_inputs;
    dpls_server_command_cache_t command_cache;
    dpls_server_journal_t journal;
    bool critical_fault;
    uint32_t boot_ms;
    uint32_t now_ms;
    uint8_t tx_encoded[DPLS_MAX_FRAME];
} dpls_server_t;

void dpls_server_init(dpls_server_t *server, const dpls_hal_t *hal, uint32_t now_ms);
void dpls_server_connected(dpls_server_t *server, uint32_t now_ms);
void dpls_server_disconnected(dpls_server_t *server, uint32_t now_ms);
void dpls_server_tick(dpls_server_t *server, uint32_t now_ms);
bool dpls_server_receive(dpls_server_t *server, const uint8_t *frame, size_t length, uint32_t now_ms);
void dpls_server_log(dpls_server_t *server, uint8_t type, uint8_t parameter);
bool dpls_server_authenticated(const dpls_server_t *server);

#endif
