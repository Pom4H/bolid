#ifndef DPLS_SIM_BOARD_H
#define DPLS_SIM_BOARD_H

#include "dpls_led.h"
#include "dpls_server.h"
#include "phy6252_emu.h"

#include <stddef.h>
#include <stdint.h>

/* DPLS product on top of the reusable PHY6252 emulator.
 * Queue sizes and ATT pacing come from phy6252_emu (same as the chip). */
#define DPLS_SIM_RX_QUEUE_DEPTH PHY6252_EMU_RX_DEPTH
#define DPLS_SIM_RX_SLOT_SIZE PHY6252_EMU_RX_SLOT
#define DPLS_SIM_TX_QUEUE_DEPTH PHY6252_EMU_TX_DEPTH
#define DPLS_SIM_TX_SLOT_SIZE PHY6252_EMU_TX_SLOT
#define DPLS_SIM_TX_CONFIRM_TIMEOUT_MS PHY6252_EMU_INDICATE_TIMEOUT_MS
#define DPLS_SIM_TX_NOTIFY_PACE_MS PHY6252_EMU_NOTIFY_PACE_MS
#define DPLS_SIM_HW_REVISION 2u

typedef void (*dpls_sim_tx_fn)(void *context, const uint8_t *frame, size_t length);
typedef void (*dpls_sim_disconnect_fn)(void *context);

typedef enum {
    DPLS_SIM_RNG_LCG = 0,
    DPLS_SIM_RNG_SEQUENTIAL = 1,
} dpls_sim_rng_t;

typedef struct {
    uint32_t device_id;
    const char *name;
    uint8_t fw_major;
    uint8_t fw_minor;
    uint8_t fw_patch;
    dpls_sim_rng_t rng;
    uint32_t lcg_seed;
    uint8_t sequential_start;
    dpls_sim_tx_fn emit_tx;
    dpls_sim_disconnect_fn on_disconnect;
    void *callback_context;
} dpls_sim_board_config_t;

typedef struct {
    dpls_server_t server;
    dpls_led_t led;
    dpls_sim_board_config_t config;
    uint32_t now_ms;
    uint32_t rng;
    uint8_t next_random;
    bool encrypted;
    bool auth_locked;
    bool connected;
    bool led_level;
    bool identify_active;
    bool real_short;
    bool reserve_low;
    dpls_settings_state_t settings_state;
    char name[DPLS_NAME_MAX + 1u];
    uint8_t salt[DPLS_AUTH_SALT_SIZE];
    uint8_t verifier[DPLS_AUTH_PROOF_SIZE];
    dpls_mode_t hardware_mode;
    bool gpio_iso_1;
    bool gpio_iso_2;
    bool gpio_iso_t;
    bool gpio_kz_1;
    bool gpio_kz_2;
    bool gpio_kz_t;
    bool gpio_led_r;
    bool gpio_led_g;
    bool gpio_led_b;
    dpls_power_t power;
    uint16_t line_mv;
    uint16_t port2_mv;
    uint16_t port_t_mv;
    uint16_t reserve_mv;
    uint8_t line_samples;
    uint8_t port2_samples;
    uint8_t port_t_samples;
    uint8_t vcap_samples;
    dpls_event_t events[DPLS_EVENT_CAPACITY];
    uint16_t event_count;
    uint32_t next_event_sequence;
    unsigned drop_next_tx;
    unsigned duplicate_next_tx;
    size_t short_next_tx;
    phy6252_emu_t radio;
    uint16_t response_count;
    uint16_t diagnostic_errors;
} dpls_sim_board_t;

void dpls_sim_board_init(dpls_sim_board_t *board, const dpls_sim_board_config_t *config);
void dpls_sim_board_connect(dpls_sim_board_t *board);
void dpls_sim_board_disconnect(dpls_sim_board_t *board);
bool dpls_sim_board_push_rx(dpls_sim_board_t *board, const uint8_t *frame, size_t length);
void dpls_sim_board_process_rx(dpls_sim_board_t *board);
void dpls_sim_board_process_tx(dpls_sim_board_t *board);
void dpls_sim_board_tx_confirmed(dpls_sim_board_t *board);
void dpls_sim_board_tick(dpls_sim_board_t *board, uint32_t delta_ms);
void dpls_sim_board_run_after_write(dpls_sim_board_t *board);
void dpls_sim_board_refresh_led(dpls_sim_board_t *board);

#endif
