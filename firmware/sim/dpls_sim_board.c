#include "dpls_sim_board.h"

#include "dpls_hmac.h"

#include <string.h>

/* PBKDF2-HMAC-SHA256("TestDpls01", salt 0x40..0x4F, 10000, 32) — the E2E
 * factory password. Stored as the board stores SNV: verifier only. */
static const uint8_t k_factory_salt[DPLS_AUTH_SALT_SIZE] = {
    0x40u, 0x41u, 0x42u, 0x43u, 0x44u, 0x45u, 0x46u, 0x47u,
    0x48u, 0x49u, 0x4Au, 0x4Bu, 0x4Cu, 0x4Du, 0x4Eu, 0x4Fu,
};
static const uint8_t k_factory_verifier[DPLS_AUTH_PROOF_SIZE] = {
    0x8Cu, 0xDBu, 0xEFu, 0xC8u, 0x4Eu, 0xF4u, 0x2Fu, 0x97u,
    0x14u, 0x30u, 0x9Eu, 0xF0u, 0x80u, 0x51u, 0xBBu, 0x5Cu,
    0xC6u, 0x93u, 0x6Au, 0xA6u, 0x71u, 0xF7u, 0xDCu, 0xA4u,
    0x2Eu, 0xCCu, 0x85u, 0x60u, 0x98u, 0x93u, 0x3Bu, 0x1Eu,
};

static void copy_name(char out[DPLS_NAME_MAX + 1u], const char *name)
{
    size_t i;
    for (i = 0; i < DPLS_NAME_MAX && name[i] != '\0'; ++i) out[i] = name[i];
    for (; i <= DPLS_NAME_MAX; ++i) out[i] = '\0';
}

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

static void apply_mode_outputs(dpls_sim_board_t *board, dpls_mode_t mode)
{
    /* Same break-before-make as firmware/phy6252/dpls_phy6252_app.c apply_mode. */
    board->gpio_iso_1 = false;
    board->gpio_iso_2 = false;
    board->gpio_iso_t = false;
    board->gpio_kz_1 = false;
    board->gpio_kz_2 = false;
    board->gpio_kz_t = false;
    switch (mode) {
    case DPLS_MODE_OPEN_T:
        board->gpio_iso_t = true;
        break;
    case DPLS_MODE_OPEN_MAIN:
        board->gpio_iso_2 = true;
        break;
    case DPLS_MODE_SHORT_1:
        board->gpio_kz_1 = true;
        break;
    case DPLS_MODE_SHORT_2:
        board->gpio_kz_2 = true;
        break;
    case DPLS_MODE_SHORT_T:
        board->gpio_kz_t = true;
        break;
    case DPLS_MODE_NORMAL:
    default:
        break;
    }
}

static void apply_led_scene(dpls_sim_board_t *board)
{
    dpls_led_set(
        &board->led,
        board->identify_active ? DPLS_LED_SCENE_IDENTIFY : scene_for_mode(board->hardware_mode),
        board->power == DPLS_POWER_RESERVE || board->reserve_low,
        board->now_ms);
}

static void led_output(void *context, bool on)
{
    dpls_sim_board_t *board = context;
    board->led_level = on;
    board->gpio_led_r = false;
    board->gpio_led_g = on;
    board->gpio_led_b = false;
}

static bool link_encrypted(void *context)
{
    return ((dpls_sim_board_t *)context)->encrypted;
}

static bool link_indicate(void *context, const uint8_t *frame, size_t length)
{
    dpls_sim_board_t *board = context;

    if (length > PHY6252_EMU_TX_SLOT) {
        return false;
    }
    return phy6252_emu_enqueue_tx(&board->radio, frame, (uint16_t)length);
}

static void link_disconnect(void *context)
{
    dpls_sim_board_t *board = context;
    board->connected = false;
    phy6252_emu_disconnect(&board->radio);
    if (board->config.on_disconnect) board->config.on_disconnect(board->config.callback_context);
    dpls_server_disconnected(&board->server, board->now_ms);
}

static bool hardware_apply_mode(void *context, dpls_mode_t mode)
{
    dpls_sim_board_t *board = context;
    apply_mode_outputs(board, mode);
    board->hardware_mode = mode;
    apply_led_scene(board);
    return true;
}

static void hardware_safe_normal(void *context)
{
    dpls_sim_board_t *board = context;
    if (board->hardware_mode != DPLS_MODE_NORMAL) {
        apply_mode_outputs(board, DPLS_MODE_NORMAL);
        board->hardware_mode = DPLS_MODE_NORMAL;
        apply_led_scene(board);
    }
}

static void radio_att_pdu(void *context, const uint8_t *data, uint16_t length, bool notify)
{
    dpls_sim_board_t *board = context;
    size_t n = length;

    (void)notify;
    if (board->short_next_tx != 0u && board->short_next_tx < n) {
        n = board->short_next_tx;
        board->short_next_tx = 0u;
    }
    if (board->config.emit_tx != NULL) {
        board->config.emit_tx(board->config.callback_context, data, n);
        ++board->response_count;
        if (board->duplicate_next_tx != 0u) {
            --board->duplicate_next_tx;
            board->config.emit_tx(board->config.callback_context, data, n);
            ++board->response_count;
        }
    }
}

static void radio_gatt_write(void *context, const uint8_t *data, uint16_t length)
{
    dpls_sim_board_t *board = context;

    (void)dpls_server_receive(&board->server, data, length, board->now_ms);
}

static void radio_indicate_overflow(void *context)
{
    dpls_sim_board_t *board = context;

    hardware_safe_normal(board);
    board->connected = false;
    phy6252_emu_disconnect(&board->radio);
    if (board->config.on_disconnect != NULL) {
        board->config.on_disconnect(board->config.callback_context);
    }
    dpls_server_disconnected(&board->server, board->now_ms);
}

static void bind_radio(dpls_sim_board_t *board)
{
    phy6252_emu_hooks_t hooks;

    memset(&hooks, 0, sizeof(hooks));
    hooks.on_att_pdu = radio_att_pdu;
    hooks.on_gatt_write = radio_gatt_write;
    hooks.on_indicate_overflow = radio_indicate_overflow;
    hooks.context = board;
    phy6252_emu_init(&board->radio, &hooks);
}

static uint16_t line_mv(void *context) { return ((dpls_sim_board_t *)context)->line_mv; }
static uint16_t port2_mv(void *context) { return ((dpls_sim_board_t *)context)->port2_mv; }
static uint16_t port_t_mv(void *context) { return ((dpls_sim_board_t *)context)->port_t_mv; }
static uint16_t reserve_mv(void *context) { return ((dpls_sim_board_t *)context)->reserve_mv; }
static dpls_power_t power_source(void *context) { return ((dpls_sim_board_t *)context)->power; }
static bool reserve_low(void *context) { return ((dpls_sim_board_t *)context)->reserve_low; }
static bool real_short(void *context) { return ((dpls_sim_board_t *)context)->real_short; }

static uint8_t measurement_validity(void *context)
{
    dpls_sim_board_t *board = context;
    uint8_t flags = 0;
    if (board->line_samples != 0u) {
        flags |= DPLS_STATE_PORT_1_VALID | DPLS_STATE_POWER_VALID | DPLS_STATE_AUTOISO_VALID;
    }
    if (board->port2_samples != 0u) flags |= DPLS_STATE_PORT_2_VALID;
    if (board->port_t_samples != 0u) flags |= DPLS_STATE_PORT_T_VALID;
    if (board->vcap_samples != 0u) flags |= DPLS_STATE_RESERVE_VOLTAGE_VALID;
    return flags;
}

static void identify_led(void *context, bool enabled)
{
    dpls_sim_board_t *board = context;
    board->identify_active = enabled;
    apply_led_scene(board);
}

static void device_info(void *context, dpls_device_info_t *out)
{
    dpls_sim_board_t *board = context;
    memset(out, 0, sizeof(*out));
    out->device_id = board->config.device_id;
    out->fw_major = board->config.fw_major;
    out->fw_minor = board->config.fw_minor;
    out->fw_patch = board->config.fw_patch;
    out->hw_revision = DPLS_SIM_HW_REVISION;
    out->capabilities = DPLS_CAP_ADC_PRESENT | DPLS_CAP_MULTI_VOLTAGE_REPORT | DPLS_CAP_HOST_SIM;
}

static dpls_settings_state_t settings_state(void *context)
{
    return ((dpls_sim_board_t *)context)->settings_state;
}

static void settings_salt(void *context, uint8_t out[DPLS_AUTH_SALT_SIZE])
{
    memcpy(out, ((dpls_sim_board_t *)context)->salt, DPLS_AUTH_SALT_SIZE);
}

static bool settings_write(void *context, const char *name,
                           const uint8_t salt[DPLS_AUTH_SALT_SIZE],
                           const uint8_t verifier[DPLS_AUTH_PROOF_SIZE])
{
    dpls_sim_board_t *board = context;
    copy_name(board->name, name);
    memcpy(board->salt, salt, DPLS_AUTH_SALT_SIZE);
    memcpy(board->verifier, verifier, DPLS_AUTH_PROOF_SIZE);
    board->settings_state = DPLS_SETTINGS_VALID;
    return true;
}

static void settings_name(void *context, char out[DPLS_NAME_MAX + 1u])
{
    memcpy(out, ((dpls_sim_board_t *)context)->name, DPLS_NAME_MAX + 1u);
}

static bool settings_set_name(void *context, const char *name)
{
    dpls_sim_board_t *board = context;
    copy_name(board->name, name);
    return true;
}

static bool settings_set_password(void *context,
                                  const uint8_t salt[DPLS_AUTH_SALT_SIZE],
                                  const uint8_t verifier[DPLS_AUTH_PROOF_SIZE])
{
    dpls_sim_board_t *board = context;
    memcpy(board->salt, salt, DPLS_AUTH_SALT_SIZE);
    memcpy(board->verifier, verifier, DPLS_AUTH_PROOF_SIZE);
    return true;
}

static bool random_bytes(void *context, uint8_t *out, size_t length)
{
    dpls_sim_board_t *board = context;
    size_t i;
    for (i = 0; i < length; ++i) {
        if (board->config.rng == DPLS_SIM_RNG_SEQUENTIAL) {
            out[i] = board->next_random++;
        } else {
            board->rng = board->rng * 1664525u + 1013904223u;
            out[i] = (uint8_t)(board->rng >> 24);
        }
    }
    return true;
}

static bool verify_proof(void *context,
                         const uint8_t device_nonce[DPLS_AUTH_NONCE_SIZE],
                         const uint8_t client_nonce[DPLS_AUTH_NONCE_SIZE],
                         uint32_t session_id,
                         const uint8_t proof[DPLS_AUTH_PROOF_SIZE])
{
    dpls_sim_board_t *board = context;
    uint8_t signed_data[36];
    uint8_t expected[32];
    uint8_t difference = 0;
    uint8_t i;
    if (board->settings_state != DPLS_SETTINGS_VALID) return false;
    memcpy(signed_data, device_nonce, 16);
    memcpy(signed_data + 16, client_nonce, 16);
    signed_data[32] = (uint8_t)session_id;
    signed_data[33] = (uint8_t)(session_id >> 8);
    signed_data[34] = (uint8_t)(session_id >> 16);
    signed_data[35] = (uint8_t)(session_id >> 24);
    if (!dpls_hmac_sha256(board->verifier, DPLS_AUTH_PROOF_SIZE, signed_data, sizeof(signed_data), expected)) {
        return false;
    }
    for (i = 0; i < 32u; ++i) difference |= (uint8_t)(expected[i] ^ proof[i]);
    memset(expected, 0, sizeof(expected));
    memset(signed_data, 0, sizeof(signed_data));
    return difference == 0;
}

static bool lock_read(void *context) { return ((dpls_sim_board_t *)context)->auth_locked; }
static bool lock_write(void *context, bool locked)
{
    ((dpls_sim_board_t *)context)->auth_locked = locked;
    return true;
}

static bool events_init(void *context, uint16_t *count, uint32_t *next_sequence)
{
    dpls_sim_board_t *board = context;
    *count = board->event_count;
    *next_sequence = board->next_event_sequence;
    return true;
}

static bool events_append(void *context, const dpls_event_t *event)
{
    dpls_sim_board_t *board = context;
    uint16_t slot = (uint16_t)((event->sequence - 1u) % DPLS_EVENT_CAPACITY);
    memcpy(&board->events[slot], event, sizeof(*event));
    if (board->event_count < DPLS_EVENT_CAPACITY) ++board->event_count;
    board->next_event_sequence = event->sequence + 1u;
    return true;
}

static bool events_read(void *context, uint32_t sequence, dpls_event_t *event)
{
    dpls_sim_board_t *board = context;
    uint16_t slot;
    if (sequence == 0u) return false;
    slot = (uint16_t)((sequence - 1u) % DPLS_EVENT_CAPACITY);
    if (board->events[slot].sequence != sequence) return false;
    memcpy(event, &board->events[slot], sizeof(*event));
    return true;
}

static void diagnostic_error(void *context, bool critical)
{
    dpls_sim_board_t *board = context;
    (void)critical;
    ++board->diagnostic_errors;
}

static dpls_hal_t make_hal(dpls_sim_board_t *board)
{
    dpls_hal_t hal;
    memset(&hal, 0, sizeof(hal));
    hal.context = board;
    hal.link.encrypted = link_encrypted;
    hal.link.indicate = link_indicate;
    hal.link.disconnect = link_disconnect;
    hal.hardware.apply_mode = hardware_apply_mode;
    hal.hardware.safe_normal = hardware_safe_normal;
    hal.hardware.voltage_mv = line_mv;
    hal.hardware.port1_voltage_mv = line_mv;
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

void dpls_sim_board_init(dpls_sim_board_t *board, const dpls_sim_board_config_t *config)
{
    dpls_hal_t hal;
    memset(board, 0, sizeof(*board));
    board->config = *config;
    if (board->config.fw_major == 0u && board->config.fw_minor == 0u && board->config.fw_patch == 0u) {
        board->config.fw_major = DPLS_FW_VERSION_MAJOR;
        board->config.fw_minor = DPLS_FW_VERSION_MINOR;
        board->config.fw_patch = DPLS_FW_VERSION_PATCH;
    }
    board->encrypted = true;
    board->settings_state = DPLS_SETTINGS_VALID;
    copy_name(board->name, config->name ? config->name : "Test-DPLS-SIM");
    memcpy(board->salt, k_factory_salt, DPLS_AUTH_SALT_SIZE);
    memcpy(board->verifier, k_factory_verifier, DPLS_AUTH_PROOF_SIZE);
    board->rng = config->lcg_seed ? config->lcg_seed : 0x44504C53u;
    board->next_random = config->sequential_start;
    board->hardware_mode = DPLS_MODE_NORMAL;
    board->power = DPLS_POWER_LINE;
    board->line_mv = 12000u;
    board->port2_mv = 12000u;
    board->port_t_mv = 12000u;
    board->reserve_mv = 5000u;
    board->next_event_sequence = 1u;
    /* Powered board has already filled ADC windows; cold-start validity 0 is
     * a PHY tick later. Soft-BLE talks to a running instrument. */
    board->line_samples = 8u;
    board->port2_samples = 8u;
    board->port_t_samples = 8u;
    board->vcap_samples = 8u;
    dpls_led_init(&board->led, led_output, board, 0u);
    bind_radio(board);
    hal = make_hal(board);
    dpls_server_init(&board->server, &hal, 0u);
}

void dpls_sim_board_connect(dpls_sim_board_t *board)
{
    board->connected = true;
    phy6252_emu_connect(&board->radio);
    dpls_server_connected(&board->server, board->now_ms);
}

void dpls_sim_board_disconnect(dpls_sim_board_t *board)
{
    board->connected = false;
    phy6252_emu_disconnect(&board->radio);
    dpls_server_disconnected(&board->server, board->now_ms);
}

bool dpls_sim_board_push_rx(dpls_sim_board_t *board, const uint8_t *frame, size_t length)
{
    if (frame == NULL || length == 0u || length > PHY6252_EMU_RX_SLOT) {
        return false;
    }
    return phy6252_emu_gatt_write(&board->radio, frame, (uint16_t)length);
}

void dpls_sim_board_process_rx(dpls_sim_board_t *board)
{
    phy6252_emu_process_rx(&board->radio);
}

void dpls_sim_board_process_tx(dpls_sim_board_t *board)
{
    board->radio.now_ms = board->now_ms;
    board->radio.connected = board->connected;
    if (!board->radio.tx.in_flight && board->radio.tx.count != 0u &&
        board->drop_next_tx != 0u) {
        --board->drop_next_tx;
        phy6252_emu_complete_tx_head(&board->radio);
        return;
    }
    phy6252_emu_process_tx(&board->radio);
}

void dpls_sim_board_tx_confirmed(dpls_sim_board_t *board)
{
    phy6252_emu_att_cfm(&board->radio);
    dpls_sim_board_process_tx(board);
}

void dpls_sim_board_tick(dpls_sim_board_t *board, uint32_t delta_ms)
{
    board->now_ms += delta_ms;
    dpls_server_tick(&board->server, board->now_ms);
    (void)dpls_led_tick(&board->led, board->now_ms);
    phy6252_emu_tick(&board->radio, board->now_ms);
    dpls_sim_board_process_tx(board);
}

void dpls_sim_board_run_after_write(dpls_sim_board_t *board)
{
    board->radio.now_ms = board->now_ms;
    board->radio.connected = board->connected;
    phy6252_emu_run_after_write(&board->radio);
}

void dpls_sim_board_refresh_led(dpls_sim_board_t *board)
{
    apply_led_scene(board);
}
