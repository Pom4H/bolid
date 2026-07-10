#include "dpls_phy6252_app.h"

#include "dpls_gatt_service.h"
#include "dpls_server.h"
#include "OSAL.h"
#include "OSAL_Timers.h"
#include "gpio.h"
#include "linkdb.h"
#include "ll_enc.h"
#include "osal_snv.h"
#include <tinycrypt/hmac.h>
#include <stddef.h>
#include <string.h>

#define DPLS_SETTINGS_MAGIC 0x534C5044u
#define DPLS_SETTINGS_SNV_ID 0x80u
#define DPLS_NAME_SIZE 32u

/* PB-03F-Kit schematic: P11 is the green RGB LED and P7 is red. Relays are
 * deliberately not assigned on the bare evaluation board. */
#define DPLS_IDENTIFY_LED GPIO_P11
#define DPLS_MODE_LED GPIO_P07

typedef struct {
    uint32_t magic;
    char name[DPLS_NAME_SIZE];
    uint8_t salt[DPLS_AUTH_SALT_SIZE];
    uint8_t verifier[DPLS_AUTH_PROOF_SIZE];
    uint16_t crc;
} dpls_settings_t;

static dpls_server_t server;
static dpls_settings_t settings;
static uint16 connection_handle = INVALID_CONNHANDLE;
static uint8 task_id;
static dpls_mode_t hardware_mode = DPLS_MODE_NORMAL;
static bool settings_valid;
static uint8 pending_rx[DPLS_MAX_FRAME];
static uint16 pending_rx_length;

static uint32_t now_ms(void) { return (uint32_t)osal_GetSystemClock(); }

static bool link_encrypted(void *context)
{
    (void)context;
    return connection_handle != INVALID_CONNHANDLE && linkDB_Encrypted(connection_handle);
}

static void safe_normal(void *context)
{
    (void)context;
    hardware_mode = DPLS_MODE_NORMAL;
    hal_gpio_write(DPLS_MODE_LED, 0);
}

static bool apply_mode(void *context, dpls_mode_t mode)
{
    (void)context;
    if (mode > DPLS_MODE_SHORT_T) return false;
    /* Evaluation-board build: expose and exercise the complete BLE state
     * machine, but never energize an unassigned relay output. */
    hardware_mode = mode;
    hal_gpio_write(DPLS_MODE_LED, mode == DPLS_MODE_NORMAL ? 0 : 1);
    return true;
}

static uint16_t voltage_mv(void *context)
{
    (void)context;
    return 0;
}

static dpls_power_t power_source(void *context)
{
    (void)context;
    return DPLS_POWER_LINE;
}

static bool reserve_low(void *context)
{
    (void)context;
    return false;
}

static void identify_led(void *context, bool enabled)
{
    (void)context;
    hal_gpio_write(DPLS_IDENTIFY_LED, enabled ? 1 : 0);
}

static void random_bytes(void *context, uint8_t *out, size_t length)
{
    uint8_t generated;
    size_t offset = 0;
    (void)context;
    while (offset < length) {
        uint8_t chunk = (uint8_t)((length - offset) > 16u ? 16u : (length - offset));
        generated = LL_ENC_GenerateTrueRandNum(out + offset, chunk);
        if (generated != SUCCESS) {
            uint32_t fallback = now_ms() ^ (uint32_t)(uintptr_t)(out + offset);
            uint8_t i;
            for (i = 0; i < chunk; ++i) {
                fallback = fallback * 1664525u + 1013904223u;
                out[offset + i] = (uint8_t)(fallback >> 24);
            }
        }
        offset += chunk;
    }
}

static bool is_settings_initialized(void *context)
{
    (void)context;
    return settings_valid;
}

static void settings_salt(void *context, uint8_t out[DPLS_AUTH_SALT_SIZE])
{
    (void)context;
    if (settings_valid) memcpy(out, settings.salt, DPLS_AUTH_SALT_SIZE);
    else memset(out, 0, DPLS_AUTH_SALT_SIZE);
}

static bool write_settings(void *context, const char *name, const uint8_t salt[16], const uint8_t verifier[32])
{
    size_t name_length;
    (void)context;
    memset(&settings, 0, sizeof(settings));
    settings.magic = DPLS_SETTINGS_MAGIC;
    name_length = strlen(name);
    if (name_length >= DPLS_NAME_SIZE) name_length = DPLS_NAME_SIZE - 1u;
    memcpy(settings.name, name, name_length);
    memcpy(settings.salt, salt, DPLS_AUTH_SALT_SIZE);
    memcpy(settings.verifier, verifier, DPLS_AUTH_PROOF_SIZE);
    settings.crc = dpls_crc16((const uint8_t *)&settings, offsetof(dpls_settings_t, crc));
    if (osal_snv_write(DPLS_SETTINGS_SNV_ID, sizeof(settings), &settings) != SUCCESS) {
        memset(&settings, 0, sizeof(settings));
        return false;
    }
    settings_valid = true;
    return true;
}

static bool verify_proof(void *context, const uint8_t device_nonce[16], const uint8_t client_nonce[16],
                         uint32_t session_id, const uint8_t proof[32])
{
    struct tc_hmac_state_struct hmac;
    uint8_t signed_data[36];
    uint8_t expected[32];
    uint8_t difference = 0;
    uint8_t i;
    (void)context;
    if (!settings_valid) return false;
    memcpy(signed_data, device_nonce, 16);
    memcpy(signed_data + 16, client_nonce, 16);
    signed_data[32] = (uint8_t)session_id;
    signed_data[33] = (uint8_t)(session_id >> 8);
    signed_data[34] = (uint8_t)(session_id >> 16);
    signed_data[35] = (uint8_t)(session_id >> 24);
    if (!tc_hmac_set_key(&hmac, settings.verifier, sizeof(settings.verifier)) ||
        !tc_hmac_init(&hmac) || !tc_hmac_update(&hmac, signed_data, sizeof(signed_data)) ||
        !tc_hmac_final(expected, sizeof(expected), &hmac)) return false;
    for (i = 0; i < sizeof(expected); ++i) difference |= (uint8_t)(expected[i] ^ proof[i]);
    memset(expected, 0, sizeof(expected));
    memset(signed_data, 0, sizeof(signed_data));
    return difference == 0;
}

static bool tx_indicate(void *context, const uint8_t *frame, size_t length)
{
    (void)context;
    return dpls_gatt_send_indication(connection_handle, frame, (uint16)length, task_id);
}

static bool tx_notify(void *context, const uint8_t *frame, size_t length)
{
    (void)context;
    return dpls_gatt_send_notification(connection_handle, frame, (uint16)length, task_id);
}

static void receive_frame(const uint8 *data, uint16 length)
{
    if (length > sizeof(pending_rx) || pending_rx_length != 0u) return;
    memcpy(pending_rx, data, length);
    pending_rx_length = length;
    osal_set_event(task_id, DPLS_PHY6252_RX_EVT);
}

void dpls_phy6252_init(uint8 new_task_id)
{
    dpls_hal_t hal;
    uint16_t expected_crc;
    task_id = new_task_id;
    connection_handle = INVALID_CONNHANDLE;
    pending_rx_length = 0;
    hal_gpio_pin_init(DPLS_IDENTIFY_LED, OEN);
    hal_gpio_pin_init(DPLS_MODE_LED, OEN);
    hal_gpio_write(DPLS_IDENTIFY_LED, 0);
    hal_gpio_write(DPLS_MODE_LED, 0);
    memset(&settings, 0, sizeof(settings));
    settings_valid = osal_snv_read(DPLS_SETTINGS_SNV_ID, sizeof(settings), &settings) == SUCCESS;
    expected_crc = dpls_crc16((const uint8_t *)&settings, offsetof(dpls_settings_t, crc));
    settings_valid = settings_valid && settings.magic == DPLS_SETTINGS_MAGIC && settings.crc == expected_crc;
    if (!settings_valid) memset(&settings, 0, sizeof(settings));

    memset(&hal, 0, sizeof(hal));
    hal.link_encrypted = link_encrypted;
    hal.hardware_apply_mode = apply_mode;
    hal.hardware_safe_normal = safe_normal;
    hal.voltage_mv = voltage_mv;
    hal.power_source = power_source;
    hal.reserve_low = reserve_low;
    hal.identify_led = identify_led;
    hal.random_bytes = random_bytes;
    hal.settings_initialized = is_settings_initialized;
    hal.settings_salt = settings_salt;
    hal.settings_write = write_settings;
    hal.verify_auth_proof = verify_proof;
    hal.tx_indicate = tx_indicate;
    hal.tx_notify = tx_notify;
    dpls_server_init(&server, &hal, now_ms());
    (void)dpls_gatt_add_service(receive_frame);
}

void dpls_phy6252_connected(uint16 conn_handle)
{
    connection_handle = conn_handle;
    dpls_server_connected(&server, now_ms());
}

void dpls_phy6252_disconnected(void)
{
    dpls_server_disconnected(&server, now_ms());
    connection_handle = INVALID_CONNHANDLE;
    pending_rx_length = 0;
}

void dpls_phy6252_process_rx(void)
{
    uint16 length = pending_rx_length;
    if (length == 0u) return;
    pending_rx_length = 0;
    (void)dpls_server_receive(&server, pending_rx, length, now_ms());
}

void dpls_phy6252_tick(void)
{
    dpls_server_tick(&server, now_ms());
}
