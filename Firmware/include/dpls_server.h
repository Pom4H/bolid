#ifndef DPLS_SERVER_H
#define DPLS_SERVER_H

#include "dpls_protocol.h"

#define DPLS_AUTH_NONCE_SIZE 16u
#define DPLS_AUTH_SALT_SIZE 16u
#define DPLS_AUTH_PROOF_SIZE 32u
#define DPLS_SESSION_TOKEN_SIZE 8u
#define DPLS_COMMAND_CACHE_SIZE 8u
#define DPLS_NAME_MAX 31u
/* Firmware version reported in DEVICE_INFO_REPORT (semantic-ish). Bump on
 * behaviour changes visible to the operator app. */
#define DPLS_FW_VERSION_MAJOR 1u
#define DPLS_FW_VERSION_MINOR 1u
#define DPLS_FW_VERSION_PATCH 1u
/* Capability bits in DEVICE_INFO_REPORT so the app can drop pretence about
 * features the hardware/firmware does not actually provide. */
enum {
    DPLS_CAP_ADC_PRESENT    = 1u << 0,
    DPLS_CAP_HW_READBACK    = 1u << 1, /* power-stage feedback (false until stage 6) */
    DPLS_CAP_ADC_CALIBRATED = 1u << 2,
};
#ifndef DPLS_EVENT_CAPACITY
#define DPLS_EVENT_CAPACITY 200u
#endif
/* Events packed into one LOG_CHUNK indication. 15 × 10 B + 3 B header = 153 B,
 * well within a 247-MTU payload; a 200-record journal exports in ~14 chunks. */
#define DPLS_LOG_CHUNK_EVENTS 15u
#define DPLS_MODE_MAX_MS 300000u
#define DPLS_SESSION_TIMEOUT_MS 10000u
#define DPLS_AUTH_BLOCK_MS 300000u
#define DPLS_AUTH_MAX_ATTEMPTS 5u
/* Minimum spacing between processed AUTH_PROOF attempts. A burst arriving faster
 * than this is rejected without a verify or a failed-attempt increment, so a
 * duplicate from a legit client is harmless while brute force is throttled. */
#define DPLS_AUTH_MIN_INTERVAL_MS 1000u
/* Commissioning window: SETUP of an uninitialised device is only accepted for
 * this long after power-on. After it closes, a power-cycle or factory reset
 * (both physical actions) re-opens it. Tighten for production; kept generous so
 * a normal commissioning flow — and the E2E — completes inside it. */
#define DPLS_SETUP_WINDOW_MS 300000u
#define DPLS_IDENTIFY_MAX_MS 60000u
#define DPLS_IDENTIFY_BLINK_MS 500u

typedef enum {
    DPLS_MODE_NORMAL = 0,
    DPLS_MODE_OPEN_T = 1,
    DPLS_MODE_OPEN_MAIN = 2,
    DPLS_MODE_SHORT_1 = 3,
    DPLS_MODE_SHORT_2 = 4,
    DPLS_MODE_SHORT_T = 5
} dpls_mode_t;

typedef enum { DPLS_POWER_LINE = 0, DPLS_POWER_RESERVE = 1 } dpls_power_t;

/* Validity mask carried in STATE_REPORT byte 16. A zero measurement is a real
 * value (0 V is a legitimate reading), so the client must not treat it as
 * "measured" unless the matching bit is set. With ADC sampling disabled every
 * bit is 0 and the app shows "—" / "Не определён" instead of fabricated data. */
enum {
    DPLS_STATE_LINE_VOLTAGE_VALID = 1u << 0,
    DPLS_STATE_RESERVE_VALID      = 1u << 1,
    DPLS_STATE_POWER_VALID        = 1u << 2,
    DPLS_STATE_AUTOISO_VALID      = 1u << 3,
    DPLS_STATE_ADC_CALIBRATED     = 1u << 4,
};
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

/* Stable, platform-supplied identity/capability facts for DEVICE_INFO_REPORT. */
typedef struct {
    uint32_t device_id;
    uint8_t fw_major;
    uint8_t fw_minor;
    uint8_t fw_patch;
    uint8_t hw_revision;
    uint8_t capabilities;
} dpls_device_info_t;

typedef struct {
    bool (*link_encrypted)(void *context);
    bool (*hardware_apply_mode)(void *context, dpls_mode_t mode);
    void (*hardware_safe_normal)(void *context);
    uint16_t (*voltage_mv)(void *context);
    dpls_power_t (*power_source)(void *context);
    bool (*reserve_low)(void *context);
    /* Optional: validity mask (DPLS_STATE_*_VALID bits) for the measured
     * fields. NULL is treated as "nothing measured yet" (mask 0). */
    uint8_t (*measurement_validity)(void *context);
    /* Reports identify mode entering/leaving. The blink shape is owned by the
     * LED driver, so this is called once on start (true) and once on stop
     * (false), not toggled per blink. */
    void (*identify_led)(void *context, bool enabled);
    bool (*random_bytes)(void *context, uint8_t *out, size_t length);
    /* Optional: true while the hardware is isolating a real downstream short
     * circuit (BRIZ-T function). NULL is treated as "never". */
    bool (*real_short_active)(void *context);
    dpls_settings_state_t (*settings_state)(void *context);
    void (*settings_salt)(void *context, uint8_t out[DPLS_AUTH_SALT_SIZE]);
    bool (*settings_write)(void *context, const char *name, const uint8_t salt[16], const uint8_t verifier[32]);
    /* Copy the current user name (NUL-terminated, up to DPLS_NAME_MAX+1 bytes).
     * Empty string if the device is not commissioned. */
    void (*settings_name)(void *context, char out[DPLS_NAME_MAX + 1u]);
    /* Read-modify-write of just the name / just the password (salt+verifier) in
     * the persisted settings record, with a read-back verify. Return false on
     * any NV failure. Optional (NULL disables NAME_SET / PASSWORD_SET). */
    bool (*settings_set_name)(void *context, const char *name);
    bool (*settings_set_password)(void *context, const uint8_t salt[16], const uint8_t verifier[32]);
    /* Fill stable identity and capability facts for DEVICE_INFO_REPORT. */
    void (*device_info)(void *context, dpls_device_info_t *out);
    bool (*verify_auth_proof)(void *context, const uint8_t device_nonce[16], const uint8_t client_nonce[16], uint32_t session_id, const uint8_t proof[32]);
    /* Optional persistent brute-force lock. auth_lock_read reports whether the
     * device booted while locked; auth_lock_write persists (true) or clears
     * (false) the marker and returns false if the NV write failed (the lock
     * would then not survive a reboot — reported as a diagnostic fault). NULL
     * on both keeps the lock RAM-only (cleared by a reboot). Cleared by factory
     * reset alongside the password. */
    bool (*auth_lock_read)(void *context);
    bool (*auth_lock_write)(void *context, bool locked);
    /* Journal storage is persistent and sequence-addressed. The server keeps
     * only metadata and streams one record at a time during BLE export. */
    bool (*event_storage_init)(void *context, uint16_t *count, uint32_t *next_sequence);
    bool (*event_storage_append)(void *context, const dpls_event_t *event);
    bool (*event_storage_read)(void *context, uint32_t sequence, dpls_event_t *event);
    bool (*tx_indicate)(void *context, const uint8_t *frame, size_t length);
    bool (*tx_notify)(void *context, const uint8_t *frame, size_t length);
    void *context;
    void (*diagnostic_error)(void *context, bool critical);
    void (*disconnect_after_setup)(void *context);
} dpls_hal_t;

typedef struct {
    bool valid;
    uint32_t session_id;
    uint32_t command_id;
    uint8_t status;
    dpls_mode_t resulting_mode;
    uint16_t remaining_seconds;
} dpls_cached_command_t;

typedef struct {
    dpls_hal_t hal;
    bool connected;
    bool authenticated;
    bool identify_active;
    bool identify_led_on;
    bool hello_received;
    bool critical_fault;
    bool power_state_known;
    dpls_power_t last_power_source;
    bool last_reserve_low;
    bool real_short;
    uint8_t failed_auth_attempts;
    uint32_t blocked_until_ms;
    uint32_t last_auth_proof_ms;
    uint32_t boot_ms;
    uint32_t now_ms;
    uint32_t session_id;
    uint32_t last_authenticated_activity_ms;
    uint32_t mode_deadline_ms;
    uint32_t identify_deadline_ms;
    uint32_t identify_blink_last_ms;
    uint32_t setup_disconnect_deadline_ms;
    uint32_t state_revision;
    uint16_t tx_sequence;
    uint8_t device_nonce[16];
    uint8_t client_nonce[16];
    uint8_t session_token[8];
    dpls_mode_t mode;
    dpls_cached_command_t command_cache[DPLS_COMMAND_CACHE_SIZE];
    uint8_t command_cache_cursor;
    /* Outgoing frames used to be allocated on the 1 KiB Cortex-M0 stack.
     * A journal response then nested this buffer with the decoded request and
     * the SDK ATT indication buffer, overflowing into events[]. */
    uint8_t tx_encoded[DPLS_MAX_FRAME];
    uint16_t event_count;
    uint32_t next_event_sequence;
    bool log_export_active;
    uint16_t log_export_count;
    uint32_t log_export_first_sequence;
} dpls_server_t;

void dpls_server_init(dpls_server_t *server, const dpls_hal_t *hal, uint32_t now_ms);
void dpls_server_connected(dpls_server_t *server, uint32_t now_ms);
void dpls_server_disconnected(dpls_server_t *server, uint32_t now_ms);
void dpls_server_tick(dpls_server_t *server, uint32_t now_ms);
bool dpls_server_receive(dpls_server_t *server, const uint8_t *frame, size_t length, uint32_t now_ms);
void dpls_server_log(dpls_server_t *server, uint8_t type, uint8_t parameter);

#endif
