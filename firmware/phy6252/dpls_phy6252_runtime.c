#include "dpls_phy6252_runtime.h"

#include "dpls_ble_identity.h"
#include "dpls_gatt_service.h"
#include "dpls_phy6252_auth.h"
#include "dpls_phy6252_events.h"
#include "dpls_phy6252_measurements.h"
#include "dpls_phy6252_outputs.h"
#include "dpls_phy6252_storage.h"
#include "dpls_phy6252_transport.h"
#include "dpls_server.h"
#include "OSAL.h"
#include "log.h"
#include <core_cm0.h>
#include <string.h>

#define DPLS_HW_REVISION 2u
#define DPLS_FACTORY_RESET_HOLD_MS 5000u

static dpls_server_t server;
static uint8 task_id;
static bool factory_reset_armed;
static uint32 factory_reset_started_ms;

static uint32 now_ms(void)
{
    return (uint32)osal_GetSystemClock();
}

static void device_info(void *context, dpls_device_info_t *out)
{
    (void)context;
    out->device_id = dpls_ble_identity_device_id();
    out->fw_major = DPLS_FW_VERSION_MAJOR;
    out->fw_minor = DPLS_FW_VERSION_MINOR;
    out->fw_patch = DPLS_FW_VERSION_PATCH;
    out->hw_revision = DPLS_HW_REVISION;
    out->capabilities = DPLS_CAP_ADC_PRESENT | DPLS_CAP_MULTI_VOLTAGE_REPORT;
    if (dpls_phy6252_measurements_line_calibrated())
        out->capabilities |= DPLS_CAP_ADC_CALIBRATED;
}

static dpls_hal_t server_hal(void)
{
    dpls_hal_t hal;
    memset(&hal, 0, sizeof(hal));

    hal.link.encrypted = dpls_phy6252_transport_encrypted;
    hal.link.indicate = dpls_phy6252_transport_indicate;
    hal.link.disconnect = dpls_phy6252_transport_disconnect;

    hal.hardware.apply_mode = dpls_phy6252_outputs_apply_mode;
    hal.hardware.safe_normal = dpls_phy6252_outputs_safe_normal;
    hal.hardware.voltage_mv = dpls_phy6252_measurements_voltage_mv;
    hal.hardware.port1_voltage_mv = dpls_phy6252_measurements_port1_mv;
    hal.hardware.port2_voltage_mv = dpls_phy6252_measurements_port2_mv;
    hal.hardware.port_t_voltage_mv = dpls_phy6252_measurements_port_t_mv;
    hal.hardware.reserve_voltage_mv = dpls_phy6252_measurements_reserve_mv;
    hal.hardware.power_source = dpls_phy6252_measurements_power_source;
    hal.hardware.reserve_low = dpls_phy6252_measurements_reserve_low;
    hal.hardware.measurement_validity = dpls_phy6252_measurements_validity;
    hal.hardware.real_short_active = dpls_phy6252_measurements_real_short;
    hal.hardware.identify_led = dpls_phy6252_outputs_identify;
    hal.hardware.device_info = device_info;

    hal.settings.state = dpls_phy6252_storage_settings_state;
    hal.settings.salt = dpls_phy6252_storage_settings_salt;
    hal.settings.write = dpls_phy6252_storage_write_settings;
    hal.settings.name = dpls_phy6252_storage_settings_name;
    hal.settings.set_name = dpls_phy6252_storage_set_name;
    hal.settings.set_password = dpls_phy6252_storage_set_password;

    hal.auth.random_bytes = dpls_phy6252_auth_random_bytes;
    hal.auth.verify_proof = dpls_phy6252_auth_verify_proof;
    hal.auth.lock_read = dpls_phy6252_storage_auth_lock_read;
    hal.auth.lock_write = dpls_phy6252_storage_auth_lock_write;

    hal.events.init = dpls_phy6252_storage_events_init;
    hal.events.append = dpls_phy6252_storage_event_append;
    hal.events.read = dpls_phy6252_storage_event_read;
    return hal;
}

static void schedule_storage_if_needed(void)
{
    if (!dpls_phy6252_transport_connected_now() &&
        dpls_phy6252_storage_has_pending_journal())
        osal_set_event(task_id, DPLS_PHY6252_STORAGE_EVT);
}

static void tick_factory_reset(uint32 now)
{
    if (!factory_reset_armed) return;
    if (!dpls_phy6252_outputs_factory_reset_active()) {
        factory_reset_armed = false;
        return;
    }
    if ((uint32)(now - factory_reset_started_ms) < DPLS_FACTORY_RESET_HOLD_MS) return;

    factory_reset_armed = false;
    if (!dpls_phy6252_storage_clear_settings()) {
        LOG("DPLS RESET storage-failed\n");
        return;
    }
    dpls_phy6252_transport_erase_bonds();
    dpls_phy6252_storage_reset_ble_bonding_keys();
    dpls_phy6252_outputs_factory_reset_latched();
    NVIC_SystemReset();
}

void dpls_phy6252_runtime_init(uint8 new_task_id)
{
    dpls_hal_t hal;
    task_id = new_task_id;

    dpls_phy6252_outputs_init();
    dpls_phy6252_storage_init();
    dpls_phy6252_transport_init(task_id);
    dpls_phy6252_measurements_init(task_id);

    factory_reset_armed = dpls_phy6252_outputs_factory_reset_active();
    factory_reset_started_ms = now_ms();

    if (dpls_phy6252_storage_settings_state(NULL) == DPLS_SETTINGS_EMPTY) {
        dpls_phy6252_transport_erase_bonds();
        dpls_phy6252_storage_reset_ble_bonding_keys();
    }

    hal = server_hal();
    dpls_server_init(&server, &hal, now_ms());
    (void)dpls_gatt_add_service(dpls_phy6252_transport_receive_frame);
    LOG("DPLS boot settings=%u\n",
        (unsigned)dpls_phy6252_storage_settings_state(NULL));
    schedule_storage_if_needed();
}

void dpls_phy6252_runtime_connected(uint16 conn_handle)
{
    dpls_phy6252_storage_set_link_active(true);
    dpls_phy6252_transport_connected(conn_handle);
    dpls_server_connected(&server, now_ms());
    LOG("DPLS CONN %u\n", conn_handle);
}

void dpls_phy6252_runtime_disconnected(void)
{
    bool authenticated = dpls_server_authenticated(&server);
    dpls_phy6252_transport_disconnected(authenticated);
    dpls_server_disconnected(&server, now_ms());
    dpls_phy6252_storage_set_link_active(false);
    LOG("DPLS DISC\n");
    schedule_storage_if_needed();
}

void dpls_phy6252_runtime_pairing_state(uint8 state, uint8 status)
{
    dpls_phy6252_transport_pairing_state(state, status);
}

void dpls_phy6252_runtime_process_rx(void)
{
    const uint8 *frame;
    uint16 length;
    if (!dpls_phy6252_transport_peek_rx(&frame, &length)) return;
    (void)dpls_server_receive(&server, frame, length, now_ms());
    dpls_phy6252_transport_consume_rx();
}

void dpls_phy6252_runtime_process_adc(void)
{
    dpls_phy6252_measurements_process();
}

void dpls_phy6252_runtime_process_tx(void)
{
    dpls_phy6252_transport_process_tx();
}

void dpls_phy6252_runtime_process_storage(void)
{
    bool more;
    if (dpls_phy6252_transport_connected_now()) return;
    more = dpls_phy6252_storage_service_journal();
    if (more) osal_set_event(task_id, DPLS_PHY6252_STORAGE_EVT);
}

void dpls_phy6252_runtime_tx_confirmed(void)
{
    dpls_phy6252_transport_tx_confirmed();
}

void dpls_phy6252_runtime_tick(void)
{
    uint32 now = now_ms();
    bool connected = dpls_phy6252_transport_connected_now();

    dpls_phy6252_transport_tick_security(dpls_server_authenticated(&server), now);
    tick_factory_reset(now);
    dpls_phy6252_measurements_tick(connected, dpls_phy6252_outputs_mode());
    dpls_server_tick(&server, now);
    dpls_phy6252_transport_tick_tx(now);
    schedule_storage_if_needed();
}

uint32 dpls_phy6252_runtime_led_tick(void)
{
    bool reserve = dpls_phy6252_measurements_power_source(NULL) == DPLS_POWER_RESERVE;
    bool auto_isolation = dpls_phy6252_measurements_real_short(NULL);
    return dpls_phy6252_outputs_led_tick(now_ms(), reserve, auto_isolation);
}
