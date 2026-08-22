#include "dpls_phy6252_runtime.h"

#include "dpls_ble_identity.h"
#include "dpls_gatt_service.h"
#include "dpls_phy6252_auth.h"
#include "dpls_phy6252_events.h"
#include "dpls_phy6252_measurements.h"
#include "dpls_phy6252_outputs.h"
#include "dpls_phy6252_storage.h"
#include "dpls_phy6252_supervisor.h"
#include "dpls_phy6252_transport.h"
#include "dpls_server.h"
#include "OSAL.h"
#include "att.h"
#include "log.h"
#include <core_cm0.h>
#include <stdint.h>
#include <string.h>

#define DPLS_HW_REVISION 2u
#define DPLS_FACTORY_RESET_HOLD_MS 5000u
/* These are sensor sampling cadences, not correctness ticks. Safety, transport
 * and factory-reset deadlines are independently scheduled as one-shot wakeups. */
#define DPLS_MEASUREMENT_ACTIVE_MS 1000u
#define DPLS_MEASUREMENT_IDLE_MS 5000u

static dpls_server_t server;
static uint8 task_id;
static bool factory_reset_armed;
static bool factory_reset_commit_wait;
static uint32 factory_reset_started_ms;
static uint32 next_measurement_ms;

static uint32 now_ms(void)
{
    return (uint32)osal_GetSystemClock();
}

static bool due(uint32 now, uint32 deadline)
{
    return (int32_t)(now - deadline) >= 0;
}

static uint32 earlier_deadline(uint32 now, uint32 current, uint32 candidate)
{
    if (candidate == 0u) return current;
    if (current == 0u) return candidate;
    return (int32_t)(candidate - now) < (int32_t)(current - now) ? candidate : current;
}

static uint32 measurement_interval_ms(void)
{
    return dpls_phy6252_transport_connected_now()
        ? DPLS_MEASUREMENT_ACTIVE_MS
        : DPLS_MEASUREMENT_IDLE_MS;
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

bool dpls_phy6252_runtime_link_active(void)
{
    return dpls_phy6252_transport_connected_now();
}

bool dpls_phy6252_runtime_flash_pending(void)
{
    return dpls_phy6252_storage_work_pending();
}

static uint8 receive_frame(const uint8 *data, uint16 length)
{
    /* Once a transaction staged security/settings persistence (or the domain
     * entered a critical fault), this connection is quiescing. Reject all new
     * application work before ATT acknowledges it. The already-queued response
     * is allowed to drain, then runtime owns the disconnect. */
    if (server.critical_fault || dpls_phy6252_storage_critical_pending() ||
        factory_reset_commit_wait)
        return ATT_ERR_INSUFFICIENT_RESOURCES;
    return dpls_phy6252_transport_receive_frame(data, length);
}

static void schedule_storage_if_needed(void)
{
    if (!dpls_phy6252_transport_connected_now() && dpls_phy6252_storage_work_pending())
        osal_set_event(task_id, DPLS_PHY6252_STORAGE_EVT);
}

static void disconnect_if_ready(void)
{
    if (!dpls_phy6252_transport_connected_now() || !dpls_phy6252_transport_tx_idle()) return;

    /* Runtime is the only owner of physical disconnect. Domain reports only a
     * critical fault; storage reports only a critical dirty fact. */
    if (server.critical_fault || dpls_phy6252_storage_critical_pending())
        dpls_phy6252_transport_disconnect(NULL);
}

static void finish_factory_reset_if_ready(void)
{
    if (!factory_reset_commit_wait || dpls_phy6252_transport_connected_now() ||
        dpls_phy6252_storage_work_pending())
        return;

    /* The only bond erase in production: physical button, link down, durable
     * settings already committed. */
    if (!dpls_phy6252_transport_factory_forget_bonds()) return;

    factory_reset_commit_wait = false;
    dpls_phy6252_outputs_factory_reset_latched();
    NVIC_SystemReset();
}

static void check_factory_reset(uint32 now)
{
    if (factory_reset_commit_wait) {
        disconnect_if_ready();
        return;
    }

    if (!factory_reset_armed) return;
    if (!dpls_phy6252_outputs_factory_reset_active()) {
        factory_reset_armed = false;
        return;
    }
    if (!due(now, factory_reset_started_ms + DPLS_FACTORY_RESET_HOLD_MS)) return;

    factory_reset_armed = false;
    dpls_phy6252_outputs_safe_normal(NULL);
    dpls_safety_force_normal(&server.safety);
    if (!dpls_phy6252_storage_clear_settings()) {
        LOG("DPLS RESET stage-failed\n");
        return;
    }

    factory_reset_commit_wait = true;
    disconnect_if_ready();
    schedule_storage_if_needed();
}

static uint32 server_next_deadline_ms(uint32 now)
{
    uint32 next = 0u;

    if (server.identify.active)
        next = earlier_deadline(now, next, server.identify.deadline_ms);

    if (server.safety.mode != DPLS_MODE_NORMAL) {
        next = earlier_deadline(now, next, server.safety.mode_deadline_ms);
        if (!server.session.connected || !server.session.authenticated) {
            /* Event handlers normally force NORMAL synchronously. Keep an
             * immediate deadline as a fail-safe invariant if they ever regress. */
            next = earlier_deadline(now, next, now);
        } else {
            next = earlier_deadline(
                now,
                next,
                server.session.last_authenticated_activity_ms + DPLS_SAFETY_SESSION_TIMEOUT_MS
            );
        }
    }
    return next;
}

void dpls_phy6252_runtime_init(uint8 new_task_id)
{
    dpls_hal_t hal;
    uint32 now;
    task_id = new_task_id;

    dpls_phy6252_outputs_init();
    dpls_phy6252_storage_init();
    dpls_phy6252_transport_init(task_id);
    dpls_phy6252_measurements_init(task_id);

    now = now_ms();
    factory_reset_armed = dpls_phy6252_outputs_factory_reset_active();
    factory_reset_commit_wait = false;
    factory_reset_started_ms = now;
    next_measurement_ms = now + DPLS_MEASUREMENT_IDLE_MS;

    hal = server_hal();
    dpls_server_init(&server, &hal, now);
    (void)dpls_gatt_add_service(receive_frame);
    dpls_phy6252_supervisor_checkpoint();
    LOG("DPLS boot settings=%u\n",
        (unsigned)dpls_phy6252_storage_settings_state(NULL));
}

void dpls_phy6252_runtime_connected(uint16 conn_handle)
{
    uint32 now = now_ms();
    dpls_phy6252_supervisor_checkpoint();
    dpls_phy6252_transport_connected(conn_handle);
    dpls_server_connected(&server, now);
    /* Get safety measurements promptly after link-up without creating a
     * permanent awake lock. ADC owns MOD_USR2 only for the conversion series. */
    next_measurement_ms = now + 1u;
    LOG("DPLS CONN %u\n", conn_handle);
}

void dpls_phy6252_runtime_disconnected(void)
{
    uint32 now = now_ms();
    dpls_phy6252_transport_disconnected();
    dpls_server_disconnected(&server, now);
    next_measurement_ms = now + DPLS_MEASUREMENT_IDLE_MS;
    LOG("DPLS DISC\n");
    schedule_storage_if_needed();
    finish_factory_reset_if_ready();
}

void dpls_phy6252_runtime_process_rx(void)
{
    const uint8 *frame;
    uint16 length;
    uint32 now;
    if (!dpls_phy6252_transport_peek_rx(&frame, &length)) return;

    now = now_ms();
    /* Deadline semantics are evaluated before the request is allowed to refresh
     * authenticated activity or observe an expired IDENTIFY state. Therefore an
     * RX callback and its already-due timer commute: packet-first and timer-first
     * converge to the same domain state. */
    dpls_server_tick(&server, now);
    (void)dpls_server_receive(&server, frame, length, now);
    dpls_phy6252_transport_consume_rx();
    /* The request itself may create a fresh mode/identify/session deadline. */
    dpls_server_tick(&server, now);
    disconnect_if_ready();
}

void dpls_phy6252_runtime_process_adc(void)
{
    uint32 now = now_ms();
    dpls_phy6252_measurements_process(server.safety.mode);
    /* A low reserve / lost measurement / real short acts at ADC completion,
     * never at an unrelated 1 Hz application tick. */
    dpls_server_tick(&server, now);
    disconnect_if_ready();
}

void dpls_phy6252_runtime_process_tx(void)
{
    dpls_phy6252_transport_process_tx();
    disconnect_if_ready();
}

void dpls_phy6252_runtime_process_storage(void)
{
    bool progressed;
    if (dpls_phy6252_transport_connected_now()) return;

    progressed = dpls_phy6252_storage_process_one(true);
    finish_factory_reset_if_ready();

    if (progressed && dpls_phy6252_storage_work_pending())
        osal_set_event(task_id, DPLS_PHY6252_STORAGE_EVT);
}

void dpls_phy6252_runtime_tx_confirmed(void)
{
    dpls_phy6252_transport_tx_confirmed();
    disconnect_if_ready();
}

void dpls_phy6252_runtime_process_timer(void)
{
    uint32 now;
    bool connected;

    dpls_phy6252_supervisor_checkpoint();
    now = now_ms();
    connected = dpls_phy6252_transport_connected_now();

    /* Sampling is periodic because physics must be observed. Everything else is
     * a one-shot deadline. This distinction is the RC9 power/reliability rule. */
    if (due(now, next_measurement_ms)) {
        dpls_phy6252_measurements_tick(connected, server.safety.mode);
        next_measurement_ms = now + measurement_interval_ms();
    }

    check_factory_reset(now);
    dpls_server_tick(&server, now);
    dpls_phy6252_transport_check_deadlines(now);
    disconnect_if_ready();
    schedule_storage_if_needed();
    finish_factory_reset_if_ready();
}

uint32 dpls_phy6252_runtime_next_wakeup_ms(void)
{
    uint32 now = now_ms();
    uint32 next = next_measurement_ms;
    uint32 candidate;
    uint32 delta;

    candidate = server_next_deadline_ms(now);
    next = earlier_deadline(now, next, candidate);

    candidate = dpls_phy6252_transport_next_deadline_ms(now);
    next = earlier_deadline(now, next, candidate);

    if (factory_reset_armed)
        next = earlier_deadline(now, next, factory_reset_started_ms + DPLS_FACTORY_RESET_HOLD_MS);

    if (next == 0u || due(now, next)) return 1u;
    delta = next - now;
    return delta == 0u ? 1u : delta;
}

uint32 dpls_phy6252_runtime_led_tick(void)
{
    bool reserve = dpls_phy6252_measurements_power_source(NULL) == DPLS_POWER_RESERVE;
    bool auto_isolation = dpls_phy6252_measurements_real_short(NULL);
    return dpls_phy6252_outputs_led_tick(now_ms(), server.safety.mode,
                                         reserve, auto_isolation);
}
