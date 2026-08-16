#include "dpls_sim_board.h"

#include <ctype.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define SIM_LINE_MAX 2048u

typedef struct {
    dpls_sim_board_t board;
    bool last_led;
    dpls_mode_t last_mode;
    uint32_t last_event_seq;
} simulator_t;

static void print_hex(const char *prefix, const uint8_t *bytes, size_t length)
{
    size_t i;
    fputs(prefix, stdout);
    for (i = 0; i < length; ++i) fprintf(stdout, "%02X", bytes[i]);
    fputc('\n', stdout);
    fflush(stdout);
}

static void emit_tx(void *context, const uint8_t *frame, size_t length)
{
    (void)context;
    print_hex("TX ", frame, length);
}

static void on_disconnect(void *context)
{
    (void)context;
    puts("DISCONNECT");
    fflush(stdout);
}

static void breadcrumbs(simulator_t *sim)
{
    uint32_t seq;
    if (sim->board.hardware_mode != sim->last_mode) {
        sim->last_mode = sim->board.hardware_mode;
        fprintf(stdout, "MODE %u\n", (unsigned)sim->last_mode);
        fflush(stdout);
    }
    if (sim->board.led_level != sim->last_led) {
        sim->last_led = sim->board.led_level;
        fprintf(stdout, "LED %u\n", sim->last_led ? 1u : 0u);
        fflush(stdout);
    }
    seq = sim->board.next_event_sequence;
    while (sim->last_event_seq + 1u < seq) {
        uint16_t slot;
        const dpls_event_t *event;
        ++sim->last_event_seq;
        slot = (uint16_t)((sim->last_event_seq - 1u) % DPLS_EVENT_CAPACITY);
        event = &sim->board.events[slot];
        if (event->sequence == sim->last_event_seq) {
            fprintf(stdout, "JOURNAL seq=%lu type=%u param=%u\n",
                    (unsigned long)event->sequence,
                    (unsigned)event->event_type,
                    (unsigned)event->parameter);
            fflush(stdout);
        }
    }
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

static void json_string(FILE *out, const char *text)
{
    fputc('"', out);
    for (; *text != '\0'; ++text) {
        unsigned char c = (unsigned char)*text;
        if (c == '"' || c == '\\') {
            fputc('\\', out);
            fputc((char)c, out);
        } else if (c >= 32u) {
            fputc((char)c, out);
        }
    }
    fputc('"', out);
}

static void print_snapshot(simulator_t *sim)
{
    const phy6252_emu_t *radio = &sim->board.radio;
    fputs("SNAPSHOT ", stdout);
    fprintf(stdout,
            "{\"now_ms\":%lu,\"connected\":%u,\"auth\":%u,\"encrypted\":%u,"
            "\"mode\":%u,\"power\":%u,\"reserve_low\":%u,\"real_short\":%u,"
            "\"identify\":%u,\"led\":%u,\"line_mv\":%u,\"port2_mv\":%u,"
            "\"port_t_mv\":%u,\"reserve_mv\":%u,\"events\":%u,\"name\":",
            (unsigned long)sim->board.now_ms,
            sim->board.connected ? 1u : 0u,
            dpls_server_authenticated(&sim->board.server) ? 1u : 0u,
            sim->board.encrypted ? 1u : 0u,
            (unsigned)sim->board.hardware_mode,
            (unsigned)sim->board.power,
            sim->board.reserve_low ? 1u : 0u,
            sim->board.real_short ? 1u : 0u,
            sim->board.identify_active ? 1u : 0u,
            sim->board.led_level ? 1u : 0u,
            (unsigned)sim->board.line_mv,
            (unsigned)sim->board.port2_mv,
            (unsigned)sim->board.port_t_mv,
            (unsigned)sim->board.reserve_mv,
            (unsigned)sim->board.event_count);
    json_string(stdout, sim->board.name);
    fprintf(stdout,
            ",\"device_id\":%lu,\"fw\":\"%u.%u.%u\"",
            (unsigned long)sim->board.config.device_id,
            (unsigned)sim->board.config.fw_major,
            (unsigned)sim->board.config.fw_minor,
            (unsigned)sim->board.config.fw_patch);
    fprintf(stdout,
            ",\"gpio\":{\"iso1\":%u,\"iso2\":%u,\"isoT\":%u,\"kz1\":%u,\"kz2\":%u,\"kzT\":%u,"
            "\"ledR\":%u,\"ledG\":%u,\"ledB\":%u}",
            sim->board.gpio_iso_1 ? 1u : 0u,
            sim->board.gpio_iso_2 ? 1u : 0u,
            sim->board.gpio_iso_t ? 1u : 0u,
            sim->board.gpio_kz_1 ? 1u : 0u,
            sim->board.gpio_kz_2 ? 1u : 0u,
            sim->board.gpio_kz_t ? 1u : 0u,
            sim->board.gpio_led_r ? 1u : 0u,
            sim->board.gpio_led_g ? 1u : 0u,
            sim->board.gpio_led_b ? 1u : 0u);
    fprintf(stdout,
            ",\"radio\":{\"connected\":%u,\"cccd\":%u,\"notify\":%u,"
            "\"rx\":%u,\"tx\":%u,\"inflight\":%u,\"inflight_since_ms\":%lu,"
            "\"att_sent\":%u,\"snv_dirty\":%u,\"stack_bytes\":%u,"
            "\"notify_pace_ms\":%u,\"indicate_timeout_ms\":%u}}\n",
            radio->connected ? 1u : 0u,
            (unsigned)radio->cccd,
            phy6252_emu_cccd_notify(radio) ? 1u : 0u,
            (unsigned)radio->rx.count,
            (unsigned)radio->tx.count,
            radio->tx.in_flight ? 1u : 0u,
            (unsigned long)radio->tx.in_flight_since_ms,
            radio->att_sent,
            radio->snv_dirty ? 1u : 0u,
            PHY6252_EMU_APP_STACK_BYTES,
            PHY6252_EMU_NOTIFY_PACE_MS,
            PHY6252_EMU_INDICATE_TIMEOUT_MS);
    fflush(stdout);
}

static void done(void)
{
    puts("DONE");
    fflush(stdout);
}

static void fill_default_name(char *out, size_t capacity, uint32_t device_id)
{
    static const char HEX[] = "0123456789ABCDEF";
    uint16_t tag = (uint16_t)(device_id & 0xffffu);
    const char *prefix = "Test-DPLS-";
    size_t i;
    for (i = 0; prefix[i] != '\0' && i + 4u < capacity; ++i) out[i] = prefix[i];
    if (i + 4u >= capacity) {
        out[0] = '\0';
        return;
    }
    out[i++] = HEX[(tag >> 12) & 0xfu];
    out[i++] = HEX[(tag >> 8) & 0xfu];
    out[i++] = HEX[(tag >> 4) & 0xfu];
    out[i++] = HEX[tag & 0xfu];
    out[i] = '\0';
}

static void copy_name_arg(char *out, const char *name)
{
    size_t i;
    for (i = 0; i < DPLS_NAME_MAX && name[i] != '\0'; ++i) out[i] = name[i];
    out[i] = '\0';
}

static int parse_fw(const char *text, uint8_t *major, uint8_t *minor, uint8_t *patch)
{
    unsigned a = 0u;
    unsigned b = 0u;
    unsigned c = 0u;
    if (sscanf(text, "%u.%u.%u", &a, &b, &c) != 3) return 0;
    if (a > 255u || b > 255u || c > 255u) return 0;
    *major = (uint8_t)a;
    *minor = (uint8_t)b;
    *patch = (uint8_t)c;
    return 1;
}

static void sim_boot(simulator_t *sim, const dpls_sim_board_config_t *config)
{
    memset(sim, 0, sizeof(*sim));
    dpls_sim_board_init(&sim->board, config);
    sim->last_led = sim->board.led_level;
    sim->last_mode = sim->board.hardware_mode;
    sim->last_event_seq = 0u;
    puts("READY DPLS2");
    breadcrumbs(sim);
}

static bool handle_line(simulator_t *sim, const char *line)
{
    uint8_t frame[DPLS_MAX_FRAME];
    if (strncmp(line, "CONNECT", 7) == 0) {
        dpls_sim_board_connect(&sim->board);
        breadcrumbs(sim);
        done();
    } else if (strncmp(line, "DISCONNECT", 10) == 0) {
        dpls_sim_board_disconnect(&sim->board);
        breadcrumbs(sim);
        done();
    } else if (strncmp(line, "ENCRYPT ", 8) == 0) {
        sim->board.encrypted = strtoul(line + 8, NULL, 10) != 0u;
        done();
    } else if (strncmp(line, "CONFIRM", 7) == 0) {
        dpls_sim_board_tx_confirmed(&sim->board);
        breadcrumbs(sim);
        done();
    } else if (strncmp(line, "FRAME ", 6) == 0) {
        size_t length = parse_hex(line + 6, frame, sizeof(frame));
        bool accepted = length != 0u && dpls_sim_board_push_rx(&sim->board, frame, length);
        if (accepted) dpls_sim_board_run_after_write(&sim->board);
        breadcrumbs(sim);
        fprintf(stdout, "ACCEPT %u\n", accepted ? 1u : 0u);
        done();
    } else if (strncmp(line, "TICK ", 5) == 0) {
        dpls_sim_board_tick(&sim->board, (uint32_t)strtoul(line + 5, NULL, 10));
        breadcrumbs(sim);
        done();
    } else if (strncmp(line, "LAB", 3) == 0 &&
               (line[3] == '\0' || isspace((unsigned char)line[3]))) {
        /* USB-powered PB-03F as captured on v1.3.0-rc.1: reserve ticks,
         * +1 ~0.37 V, other rails ~0.03 V, "Низкий резерв". */
        sim->board.power = DPLS_POWER_RESERVE;
        sim->board.reserve_low = true;
        sim->board.line_mv = 370u;
        sim->board.port2_mv = 30u;
        sim->board.port_t_mv = 30u;
        sim->board.reserve_mv = 30u;
        dpls_sim_board_refresh_led(&sim->board);
        breadcrumbs(sim);
        done();
    } else if (strncmp(line, "POWER RESERVE", 13) == 0) {
        sim->board.power = DPLS_POWER_RESERVE;
        dpls_sim_board_refresh_led(&sim->board);
        breadcrumbs(sim);
        done();
    } else if (strncmp(line, "POWER LINE", 10) == 0) {
        sim->board.power = DPLS_POWER_LINE;
        sim->board.reserve_low = false;
        sim->board.line_mv = 12000u;
        sim->board.port2_mv = 12000u;
        sim->board.port_t_mv = 12000u;
        sim->board.reserve_mv = 5000u;
        dpls_sim_board_refresh_led(&sim->board);
        breadcrumbs(sim);
        done();
    } else if (strncmp(line, "RESERVE_LOW ", 12) == 0) {
        sim->board.reserve_low = strtoul(line + 12, NULL, 10) != 0u;
        dpls_sim_board_refresh_led(&sim->board);
        breadcrumbs(sim);
        done();
    } else if (strncmp(line, "REAL_SHORT ", 11) == 0) {
        sim->board.real_short = strtoul(line + 11, NULL, 10) != 0u;
        done();
    } else if (strncmp(line, "SETTINGS EMPTY", 14) == 0) {
        sim->board.settings_state = DPLS_SETTINGS_EMPTY;
        done();
    } else if (strncmp(line, "SETTINGS VALID", 14) == 0) {
        sim->board.settings_state = DPLS_SETTINGS_VALID;
        done();
    } else if (strncmp(line, "CCCD ", 5) == 0) {
        unsigned cfg = (unsigned)strtoul(line + 5, NULL, 0);
        phy6252_emu_set_cccd(&sim->board.radio, (uint16_t)cfg);
        done();
    } else if (strncmp(line, "FAULT DROP", 10) == 0) {
        sim->board.drop_next_tx = 1u;
        done();
    } else if (strncmp(line, "FAULT DUP", 9) == 0) {
        sim->board.duplicate_next_tx = 1u;
        done();
    } else if (strncmp(line, "FAULT SHORT ", 12) == 0) {
        sim->board.short_next_tx = (size_t)strtoul(line + 12, NULL, 10);
        done();
    } else if (strncmp(line, "STATE", 5) == 0) {
        fprintf(stdout,
                "STATE mode=%u led=%u auth=%u encrypted=%u power=%u reserve_low=%u "
                "line_mv=%u port2_mv=%u port_t_mv=%u reserve_mv=%u identify=%u "
                "now=%lu inflight=%u notify=%u\n",
                (unsigned)sim->board.hardware_mode,
                sim->board.led_level ? 1u : 0u,
                dpls_server_authenticated(&sim->board.server) ? 1u : 0u,
                sim->board.encrypted ? 1u : 0u,
                (unsigned)sim->board.power,
                sim->board.reserve_low ? 1u : 0u,
                (unsigned)sim->board.line_mv,
                (unsigned)sim->board.port2_mv,
                (unsigned)sim->board.port_t_mv,
                (unsigned)sim->board.reserve_mv,
                sim->board.identify_active ? 1u : 0u,
                (unsigned long)sim->board.now_ms,
                sim->board.radio.tx.in_flight ? 1u : 0u,
                phy6252_emu_cccd_notify(&sim->board.radio) ? 1u : 0u);
        done();
    } else if (strncmp(line, "SNAPSHOT", 8) == 0) {
        print_snapshot(sim);
        done();
    } else if (strncmp(line, "QUIT", 4) == 0) {
        return false;
    } else {
        puts("ERROR unknown-command");
        done();
    }
    return true;
}

int main(int argc, char **argv)
{
    simulator_t sim;
    dpls_sim_board_config_t config;
    char name[DPLS_NAME_MAX + 1u];
    char line[SIM_LINE_MAX];
    int i;

    memset(&config, 0, sizeof(config));
    config.device_id = 0x00001234u;
    config.fw_major = DPLS_FW_VERSION_MAJOR;
    config.fw_minor = DPLS_FW_VERSION_MINOR;
    config.fw_patch = DPLS_FW_VERSION_PATCH;
    config.rng = DPLS_SIM_RNG_LCG;
    config.lcg_seed = 0x44504C53u;
    config.emit_tx = emit_tx;
    config.on_disconnect = on_disconnect;
    config.callback_context = &sim;
    name[0] = '\0';

    for (i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--id") == 0 && i + 1 < argc) {
            config.device_id = (uint32_t)strtoul(argv[++i], NULL, 0);
        } else if (strcmp(argv[i], "--name") == 0 && i + 1 < argc) {
            copy_name_arg(name, argv[++i]);
        } else if (strcmp(argv[i], "--fw") == 0 && i + 1 < argc) {
            if (!parse_fw(argv[++i], &config.fw_major, &config.fw_minor, &config.fw_patch)) {
                fprintf(stderr, "dpls_simulator: bad --fw, expected MAJOR.MINOR.PATCH\n");
                return 2;
            }
        } else {
            fprintf(stderr, "dpls_simulator: unknown arg %s\n", argv[i]);
            fprintf(stderr, "usage: dpls_simulator [--id HEX] [--name STR] [--fw X.Y.Z]\n");
            return 2;
        }
    }
    if (name[0] == '\0') fill_default_name(name, sizeof(name), config.device_id);
    config.name = name;

    setvbuf(stdout, NULL, _IOLBF, 0);
    sim_boot(&sim, &config);
    while (fgets(line, sizeof(line), stdin) != NULL) {
        if (!handle_line(&sim, line)) break;
    }
    return 0;
}
