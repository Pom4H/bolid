#include "dpls_led.h"
#include "dpls_server.h"
#include "zmu_vectors.h"

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define SIM_EVENT_COUNT DPLS_EVENT_CAPACITY

extern void initialise_monitor_handles(void);

typedef struct {
    dpls_server_t server;
    dpls_led_t led;
    dpls_event_t events[SIM_EVENT_COUNT];
    uint16_t event_count;
    uint32_t next_event_sequence;
    uint32_t now_ms;
    uint8_t next_random;
    dpls_mode_t hardware_mode;
    bool led_level;
    bool encrypted;
    bool disconnected;
    uint16_t response_count;
    uint16_t diagnostic_errors;
} simulator_t;

static dpls_led_scene_t scene_for_mode(dpls_mode_t mode)
{
    switch (mode) {
    case DPLS_MODE_OPEN_T: return DPLS_LED_SCENE_OPEN_T;
    case DPLS_MODE_OPEN_MAIN: return DPLS_LED_SCENE_OPEN_MAIN;
    case DPLS_MODE_SHORT_1: return DPLS_LED_SCENE_SHORT_1;
    case DPLS_MODE_SHORT_2: return DPLS_LED_SCENE_SHORT_2;
    case DPLS_MODE_SHORT_T: return DPLS_LED_SCENE_SHORT_T;
    case DPLS_MODE_NORMAL:
    default: return DPLS_LED_SCENE_NORMAL;
    }
}

static void led_output(void *context, bool on)
{
    ((simulator_t *)context)->led_level = on;
}

static bool encrypted(void *context)
{
    return ((simulator_t *)context)->encrypted;
}

static bool indicate(void *context, const uint8_t *frame, size_t length)
{
    simulator_t *sim = context;
    size_t i;
    ++sim->response_count;
    printf("FRAME ");
    for (i = 0; i < length; ++i) printf("%02X", frame[i]);
    printf("\n");
    return true;
}

static void disconnect_link(void *context)
{
    ((simulator_t *)context)->disconnected = true;
}

static void apply_led_scene(simulator_t *sim)
{
    dpls_led_set(&sim->led, scene_for_mode(sim->hardware_mode), false, sim->now_ms);
}

static bool apply_mode(void *context, dpls_mode_t mode)
{
    simulator_t *sim = context;
    sim->hardware_mode = mode;
    apply_led_scene(sim);
    return true;
}

static void safe_normal(void *context)
{
    simulator_t *sim = context;
    sim->hardware_mode = DPLS_MODE_NORMAL;
    apply_led_scene(sim);
}

static uint16_t line_voltage(void *context)
{
    (void)context;
    return 12000u;
}

static uint16_t reserve_voltage(void *context)
{
    (void)context;
    return 5000u;
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
    return DPLS_STATE_LINE_VOLTAGE_VALID |
           DPLS_STATE_RESERVE_VALID |
           DPLS_STATE_POWER_VALID |
           DPLS_STATE_AUTOISO_VALID |
           DPLS_STATE_ADC_CALIBRATED |
           DPLS_STATE_PORT_2_VALID |
           DPLS_STATE_PORT_T_VALID;
}

static void identify_led(void *context, bool enabled)
{
    simulator_t *sim = context;
    dpls_led_set(
        &sim->led,
        enabled ? DPLS_LED_SCENE_IDENTIFY : scene_for_mode(sim->hardware_mode),
        false,
        sim->now_ms
    );
}

static void device_info(void *context, dpls_device_info_t *out)
{
    (void)context;
    memset(out, 0, sizeof(*out));
    out->device_id = 0x12345678u;
    out->fw_major = DPLS_FW_VERSION_MAJOR;
    out->fw_minor = DPLS_FW_VERSION_MINOR;
    out->fw_patch = DPLS_FW_VERSION_PATCH;
    out->hw_revision = 2u;
    out->capabilities = DPLS_CAP_ADC_PRESENT |
                        DPLS_CAP_HW_READBACK |
                        DPLS_CAP_ADC_CALIBRATED |
                        DPLS_CAP_MULTI_VOLTAGE_REPORT;
}

static dpls_settings_state_t settings_state(void *context)
{
    (void)context;
    return DPLS_SETTINGS_VALID;
}

static void settings_salt(void *context, uint8_t out[DPLS_AUTH_SALT_SIZE])
{
    uint8_t i;
    (void)context;
    for (i = 0; i < DPLS_AUTH_SALT_SIZE; ++i) out[i] = (uint8_t)(0x40u + i);
}

static bool settings_write(
    void *context,
    const char *name,
    const uint8_t salt[16],
    const uint8_t verifier[32]
)
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
    strcpy(out, "Test-DPLS-ZMU");
}

static bool settings_set_name(void *context, const char *name)
{
    (void)context;
    (void)name;
    return true;
}

static bool settings_set_password(
    void *context,
    const uint8_t salt[16],
    const uint8_t verifier[32]
)
{
    (void)context;
    (void)salt;
    (void)verifier;
    return true;
}

static bool random_bytes(void *context, uint8_t *out, size_t length)
{
    simulator_t *sim = context;
    size_t i;
    for (i = 0; i < length; ++i) out[i] = sim->next_random++;
    return true;
}

static bool verify_proof(
    void *context,
    const uint8_t device_nonce[16],
    const uint8_t client_nonce[16],
    uint32_t session_id,
    const uint8_t proof[32]
)
{
    (void)context;
    (void)device_nonce;
    (void)client_nonce;
    (void)session_id;
    (void)proof;
    return true;
}

static bool lock_read(void *context)
{
    (void)context;
    return false;
}

static bool lock_write(void *context, bool locked)
{
    (void)context;
    (void)locked;
    return true;
}

static bool events_init(void *context, uint16_t *count, uint32_t *next_sequence)
{
    simulator_t *sim = context;
    *count = sim->event_count;
    *next_sequence = sim->next_event_sequence;
    return true;
}

static bool events_append(void *context, const dpls_event_t *event)
{
    simulator_t *sim = context;
    uint16_t slot = (uint16_t)((event->sequence - 1u) % SIM_EVENT_COUNT);
    sim->events[slot] = *event;
    if (sim->event_count < SIM_EVENT_COUNT) ++sim->event_count;
    sim->next_event_sequence = event->sequence + 1u;
    return true;
}

static bool events_read(void *context, uint32_t sequence, dpls_event_t *event)
{
    simulator_t *sim = context;
    uint16_t slot;
    if (sequence == 0u) return false;
    slot = (uint16_t)((sequence - 1u) % SIM_EVENT_COUNT);
    if (sim->events[slot].sequence != sequence) return false;
    *event = sim->events[slot];
    return true;
}

static void diagnostic_error(void *context, bool critical)
{
    simulator_t *sim = context;
    (void)critical;
    ++sim->diagnostic_errors;
}

static dpls_hal_t make_hal(simulator_t *sim)
{
    dpls_hal_t hal;
    memset(&hal, 0, sizeof(hal));
    hal.context = sim;
    hal.link.encrypted = encrypted;
    hal.link.indicate = indicate;
    hal.link.disconnect = disconnect_link;
    hal.hardware.apply_mode = apply_mode;
    hal.hardware.safe_normal = safe_normal;
    hal.hardware.voltage_mv = line_voltage;
    hal.hardware.port1_voltage_mv = line_voltage;
    hal.hardware.port2_voltage_mv = line_voltage;
    hal.hardware.port_t_voltage_mv = line_voltage;
    hal.hardware.reserve_voltage_mv = reserve_voltage;
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
    hal.events.init = events_init;
    hal.events.append = events_append;
    hal.events.read = events_read;
    hal.diagnostic_error = diagnostic_error;
    return hal;
}

static void simulator_init(simulator_t *sim)
{
    dpls_hal_t hal;
    memset(sim, 0, sizeof(*sim));
    sim->encrypted = true;
    sim->next_random = 0x10u;
    sim->next_event_sequence = 1u;
    sim->hardware_mode = DPLS_MODE_NORMAL;
    dpls_led_init(&sim->led, led_output, sim, 0u);
    hal = make_hal(sim);
    dpls_server_init(&sim->server, &hal, 0u);
    dpls_server_connected(&sim->server, 0u);
}

#define CHECK(expr) do { \
    if (!(expr)) { \
        printf("ZMU_E2E_FAIL:%s:%d:%s\n", __FILE__, __LINE__, #expr); \
        return 1; \
    } \
} while (0)

int main(void)
{
    simulator_t sim;
    uint16_t i;

    simulator_init(&sim);

    for (i = 0; i < ZMU_REQUEST_COUNT; ++i) {
        sim.now_ms += 10u;
        CHECK(dpls_server_receive(
            &sim.server,
            zmu_requests[i].data,
            zmu_requests[i].length,
            sim.now_ms
        ));

        if (i == 0u) {
            CHECK(dpls_led_tick(&sim.led, sim.now_ms) == 500u);
            CHECK(sim.led_level);
            (void)dpls_led_tick(&sim.led, sim.now_ms + 499u);
            CHECK(sim.led_level);
            (void)dpls_led_tick(&sim.led, sim.now_ms + 500u);
            CHECK(!sim.led_level);
            (void)dpls_led_tick(&sim.led, sim.now_ms + 1000u);
            CHECK(sim.led_level);
            sim.now_ms += 1000u;
        }
        if (i == 1u) {
            (void)dpls_led_tick(&sim.led, sim.now_ms);
            CHECK(!sim.led_level);
        }
        if (i == 3u) CHECK(dpls_server_authenticated(&sim.server));
        if (i == 7u) CHECK(sim.hardware_mode == DPLS_MODE_SHORT_1);
    }

    CHECK(sim.response_count == ZMU_REQUEST_COUNT);
    CHECK(sim.diagnostic_errors == 0u);
    CHECK(sim.hardware_mode == DPLS_MODE_SHORT_1);

    dpls_server_disconnected(&sim.server, sim.now_ms + 10u);
    CHECK(sim.hardware_mode == DPLS_MODE_NORMAL);

    printf("ZMU_E2E_OK responses=%u events=%u\n", sim.response_count, sim.event_count);
    return 0;
}

void SystemInit(void) {}

void _start(void)
{
    initialise_monitor_handles();
    exit(main());
}

__attribute__((used)) void _fini(void) {}
