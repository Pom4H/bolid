#include "dpls_server.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define SIM_LINE_MAX 2048u

typedef struct {
    dpls_server_t server;
    uint32_t now_ms;
    uint32_t rng;
    bool encrypted;
    bool auth_locked;
    dpls_settings_state_t settings_state;
    char name[DPLS_NAME_MAX + 1u];
    uint8_t salt[DPLS_AUTH_SALT_SIZE];
    uint8_t verifier[DPLS_AUTH_PROOF_SIZE];
    dpls_mode_t hardware_mode;
    dpls_power_t power;
    bool reserve_low;
    bool real_short;
    bool identify_led;
    uint16_t line_mv;
    uint16_t port2_mv;
    uint16_t port_t_mv;
    uint16_t reserve_mv;
    dpls_event_t events[DPLS_EVENT_CAPACITY];
    uint16_t event_count;
    uint32_t next_event_sequence;
    unsigned drop_next_tx;
    unsigned duplicate_next_tx;
    size_t short_next_tx;
} simulator_t;

static void print_hex(const char *prefix, const uint8_t *bytes, size_t length)
{
    size_t i;
    fputs(prefix, stdout);
    for (i = 0; i < length; ++i) fprintf(stdout, "%02X", bytes[i]);
    fputc('\n', stdout);
    fflush(stdout);
}

static bool link_encrypted(void *context)
{
    return ((simulator_t *)context)->encrypted;
}

static bool link_indicate(void *context, const uint8_t *frame, size_t length)
{
    simulator_t *sim = context;
    size_t delivered = length;

    if (sim->drop_next_tx != 0u) {
        --sim->drop_next_tx;
        puts("TX_DROPPED");
        fflush(stdout);
        return true;
    }
    if (sim->short_next_tx != 0u && sim->short_next_tx < delivered) {
        delivered = sim->short_next_tx;
        sim->short_next_tx = 0u;
    }
    print_hex("TX ", frame, delivered);
    if (sim->duplicate_next_tx != 0u) {
        --sim->duplicate_next_tx;
        print_hex("TX ", frame, delivered);
    }
    return true;
}

static void link_disconnect(void *context)
{
    simulator_t *sim = context;
    dpls_server_disconnected(&sim->server, sim->now_ms);
    puts("DISCONNECT");
    fflush(stdout);
}

static bool hardware_apply_mode(void *context, dpls_mode_t mode)
{
    simulator_t *sim = context;
    sim->hardware_mode = mode;
    fprintf(stdout, "MODE %u\n", (unsigned)mode);
    fflush(stdout);
    return true;
}

static void hardware_safe_normal(void *context)
{
    simulator_t *sim = context;
    if (sim->hardware_mode != DPLS_MODE_NORMAL) {
        sim->hardware_mode = DPLS_MODE_NORMAL;
        puts("MODE 0");
        fflush(stdout);
    }
}

static uint16_t line_mv(void *context) { return ((simulator_t *)context)->line_mv; }
static uint16_t port1_mv(void *context) { return ((simulator_t *)context)->line_mv; }
static uint16_t port2_mv(void *context) { return ((simulator_t *)context)->port2_mv; }
static uint16_t port_t_mv(void *context) { return ((simulator_t *)context)->port_t_mv; }
static uint16_t reserve_mv(void *context) { return ((simulator_t *)context)->reserve_mv; }
static dpls_power_t power_source(void *context) { return ((simulator_t *)context)->power; }
static bool reserve_low(void *context) { return ((simulator_t *)context)->reserve_low; }
static bool real_short(void *context) { return ((simulator_t *)context)->real_short; }

static uint8_t measurement_validity(void *context)
{
    (void)context;
    return DPLS_STATE_PORT_1_VALID |
           DPLS_STATE_PORT_2_VALID |
           DPLS_STATE_PORT_T_VALID |
           DPLS_STATE_RESERVE_VOLTAGE_VALID |
           DPLS_STATE_POWER_VALID |
           DPLS_STATE_AUTOISO_VALID;
}

static void identify_led(void *context, bool enabled)
{
    simulator_t *sim = context;
    if (sim->identify_led == enabled) return;
    sim->identify_led = enabled;
    fprintf(stdout, "LED %u\n", enabled ? 1u : 0u);
    fflush(stdout);
}

static void device_info(void *context, dpls_device_info_t *out)
{
    (void)context;
    memset(out, 0, sizeof(*out));
    out->device_id = 0x00001234u;
    out->fw_major = DPLS_FW_VERSION_MAJOR;
    out->fw_minor = DPLS_FW_VERSION_MINOR;
    out->fw_patch = DPLS_FW_VERSION_PATCH;
    out->hw_revision = 2u;
    out->capabilities = DPLS_CAP_ADC_PRESENT | DPLS_CAP_MULTI_VOLTAGE_REPORT;
}

static dpls_settings_state_t settings_state(void *context)
{
    return ((simulator_t *)context)->settings_state;
}

static void settings_salt(void *context, uint8_t out[DPLS_AUTH_SALT_SIZE])
{
    simulator_t *sim = context;
    memcpy(out, sim->salt, DPLS_AUTH_SALT_SIZE);
}

static bool settings_write(void *context, const char *name,
                           const uint8_t salt[DPLS_AUTH_SALT_SIZE],
                           const uint8_t verifier[DPLS_AUTH_PROOF_SIZE])
{
    simulator_t *sim = context;
    strncpy(sim->name, name, DPLS_NAME_MAX);
    sim->name[DPLS_NAME_MAX] = '\0';
    memcpy(sim->salt, salt, DPLS_AUTH_SALT_SIZE);
    memcpy(sim->verifier, verifier, DPLS_AUTH_PROOF_SIZE);
    sim->settings_state = DPLS_SETTINGS_VALID;
    return true;
}

static void settings_name(void *context, char out[DPLS_NAME_MAX + 1u])
{
    simulator_t *sim = context;
    memcpy(out, sim->name, sizeof(sim->name));
}

static bool settings_set_name(void *context, const char *name)
{
    simulator_t *sim = context;
    strncpy(sim->name, name, DPLS_NAME_MAX);
    sim->name[DPLS_NAME_MAX] = '\0';
    return true;
}

static bool settings_set_password(void *context,
                                  const uint8_t salt[DPLS_AUTH_SALT_SIZE],
                                  const uint8_t verifier[DPLS_AUTH_PROOF_SIZE])
{
    simulator_t *sim = context;
    memcpy(sim->salt, salt, DPLS_AUTH_SALT_SIZE);
    memcpy(sim->verifier, verifier, DPLS_AUTH_PROOF_SIZE);
    return true;
}

static bool random_bytes(void *context, uint8_t *out, size_t length)
{
    simulator_t *sim = context;
    size_t i;
    for (i = 0; i < length; ++i) {
        sim->rng = sim->rng * 1664525u + 1013904223u;
        out[i] = (uint8_t)(sim->rng >> 24);
    }
    return true;
}

static bool verify_proof(void *context,
                         const uint8_t device_nonce[DPLS_AUTH_NONCE_SIZE],
                         const uint8_t client_nonce[DPLS_AUTH_NONCE_SIZE],
                         uint32_t session_id,
                         const uint8_t proof[DPLS_AUTH_PROOF_SIZE])
{
    (void)context;
    (void)device_nonce;
    (void)client_nonce;
    (void)session_id;
    (void)proof;
    return true;
}

static bool lock_read(void *context) { return ((simulator_t *)context)->auth_locked; }
static bool lock_write(void *context, bool locked)
{
    ((simulator_t *)context)->auth_locked = locked;
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
    size_t slot = (size_t)((event->sequence - 1u) % DPLS_EVENT_CAPACITY);
    sim->events[slot] = *event;
    if (sim->event_count < DPLS_EVENT_CAPACITY) ++sim->event_count;
    sim->next_event_sequence = event->sequence + 1u;
    return true;
}

static bool events_read(void *context, uint32_t sequence, dpls_event_t *event)
{
    simulator_t *sim = context;
    size_t slot;
    if (sequence == 0u) return false;
    slot = (size_t)((sequence - 1u) % DPLS_EVENT_CAPACITY);
    if (sim->events[slot].sequence != sequence) return false;
    *event = sim->events[slot];
    return true;
}

static void diagnostic_error(void *context, bool critical)
{
    (void)context;
    fprintf(stdout, "DIAG %u\n", critical ? 1u : 0u);
    fflush(stdout);
}

static dpls_hal_t make_hal(simulator_t *sim)
{
    dpls_hal_t hal;
    memset(&hal, 0, sizeof(hal));
    hal.context = sim;
    hal.link.encrypted = link_encrypted;
    hal.link.indicate = link_indicate;
    hal.link.disconnect = link_disconnect;
    hal.hardware.apply_mode = hardware_apply_mode;
    hal.hardware.safe_normal = hardware_safe_normal;
    hal.hardware.voltage_mv = line_mv;
    hal.hardware.port1_voltage_mv = port1_mv;
    hal.hardware.port2_voltage_mv = port2_mv;
    hal.hardware.port_t_voltage_mv = port_t_mv;
    hal.hardware.reserve_voltage_mv = reserve_mv;
    hal.hardware.power_source = power_source;
    hal.hardware.reserve_low = reserve_low;
    hal.hardware.measurement_validity = measurement_validity;
    hal.hardware.identify_led = identify_led;
    hal.hardware.real_short_active = real_short;
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
    size_t i;
    memset(sim, 0, sizeof(*sim));
    sim->rng = 0xDPL5u;
    sim->encrypted = true;
    sim->settings_state = DPLS_SETTINGS_VALID;
    strcpy(sim->name, "Test-DPLS-SIM");
    for (i = 0; i < DPLS_AUTH_SALT_SIZE; ++i) sim->salt[i] = (uint8_t)(0x40u + i);
    sim->hardware_mode = DPLS_MODE_NORMAL;
    sim->power = DPLS_POWER_LINE;
    sim->line_mv = 12000u;
    sim->port2_mv = 12000u;
    sim->port_t_mv = 12000u;
    sim->reserve_mv = 5000u;
    sim->next_event_sequence = 1u;
    hal = make_hal(sim);
    dpls_server_init(&sim->server, &hal, sim->now_ms);
}

static int hex_digit(int c)
{
    if (c >= '0' && c <= '9') return c - '0';
    c = toupper(c);
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static size_t parse_hex(const char *text, uint8_t *out, size_t capacity)
{
    size_t length = 0u;
    int high = -1;
    while (*text != '\0') {
        int digit;
        if (isspace((unsigned char)*text)) {
            ++text;
            continue;
        }
        digit = hex_digit((unsigned char)*text++);
        if (digit < 0) return 0u;
        if (high < 0) {
            high = digit;
        } else {
            if (length >= capacity) return 0u;
            out[length++] = (uint8_t)((high << 4) | digit);
            high = -1;
        }
    }
    return high < 0 ? length : 0u;
}

static void done(void)
{
    puts("DONE");
    fflush(stdout);
}

int main(void)
{
    simulator_t sim;
    char line[SIM_LINE_MAX];
    uint8_t frame[DPLS_MAX_FRAME];

    setvbuf(stdout, NULL, _IOLBF, 0);
    simulator_init(&sim);
    puts("READY DPLS2");

    while (fgets(line, sizeof(line), stdin) != NULL) {
        if (strncmp(line, "CONNECT", 7) == 0) {
            dpls_server_connected(&sim.server, sim.now_ms);
            done();
        } else if (strncmp(line, "DISCONNECT", 10) == 0) {
            dpls_server_disconnected(&sim.server, sim.now_ms);
            done();
        } else if (strncmp(line, "ENCRYPT ", 8) == 0) {
            sim.encrypted = strtoul(line + 8, NULL, 10) != 0u;
            done();
        } else if (strncmp(line, "FRAME ", 6) == 0) {
            size_t length = parse_hex(line + 6, frame, sizeof(frame));
            bool accepted = length != 0u && dpls_server_receive(&sim.server, frame, length, sim.now_ms);
            fprintf(stdout, "ACCEPT %u\n", accepted ? 1u : 0u);
            done();
        } else if (strncmp(line, "TICK ", 5) == 0) {
            sim.now_ms += (uint32_t)strtoul(line + 5, NULL, 10);
            dpls_server_tick(&sim.server, sim.now_ms);
            done();
        } else if (strncmp(line, "POWER RESERVE", 13) == 0) {
            sim.power = DPLS_POWER_RESERVE;
            done();
        } else if (strncmp(line, "POWER LINE", 10) == 0) {
            sim.power = DPLS_POWER_LINE;
            done();
        } else if (strncmp(line, "RESERVE_LOW ", 12) == 0) {
            sim.reserve_low = strtoul(line + 12, NULL, 10) != 0u;
            done();
        } else if (strncmp(line, "REAL_SHORT ", 11) == 0) {
            sim.real_short = strtoul(line + 11, NULL, 10) != 0u;
            done();
        } else if (strncmp(line, "SETTINGS EMPTY", 14) == 0) {
            sim.settings_state = DPLS_SETTINGS_EMPTY;
            done();
        } else if (strncmp(line, "SETTINGS VALID", 14) == 0) {
            sim.settings_state = DPLS_SETTINGS_VALID;
            done();
        } else if (strncmp(line, "FAULT DROP", 10) == 0) {
            sim.drop_next_tx = 1u;
            done();
        } else if (strncmp(line, "FAULT DUP", 9) == 0) {
            sim.duplicate_next_tx = 1u;
            done();
        } else if (strncmp(line, "FAULT SHORT ", 12) == 0) {
            sim.short_next_tx = (size_t)strtoul(line + 12, NULL, 10);
            done();
        } else if (strncmp(line, "STATE", 5) == 0) {
            fprintf(stdout, "STATE mode=%u led=%u auth=%u encrypted=%u now=%lu\n",
                    (unsigned)sim.hardware_mode,
                    sim.identify_led ? 1u : 0u,
                    dpls_server_authenticated(&sim.server) ? 1u : 0u,
                    sim.encrypted ? 1u : 0u,
                    (unsigned long)sim.now_ms);
            done();
        } else if (strncmp(line, "QUIT", 4) == 0) {
            break;
        } else {
            puts("ERROR unknown-command");
            done();
        }
    }
    return 0;
}
