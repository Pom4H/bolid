#include "dpls_sim_board.h"
#include "zmu_vectors.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern void initialise_monitor_handles(void);

static void emit_frame(void *context, const uint8_t *frame, size_t length)
{
    size_t i;
    (void)context;
    printf("FRAME ");
    for (i = 0; i < length; ++i) printf("%02X", frame[i]);
    printf("\n");
}

#define CHECK(expr) do { \
    if (!(expr)) { \
        printf("ZMU_E2E_FAIL:%s:%d:%s\n", __FILE__, __LINE__, #expr); \
        return 1; \
    } \
} while (0)

int main(void)
{
    dpls_sim_board_t board;
    dpls_sim_board_config_t config;
    uint16_t i;
    static const dpls_mode_t mode_matrix[5] = {
        DPLS_MODE_SHORT_1,
        DPLS_MODE_SHORT_2,
        DPLS_MODE_SHORT_T,
        DPLS_MODE_OPEN_T,
        DPLS_MODE_OPEN_MAIN
    };

    memset(&config, 0, sizeof(config));
    config.device_id = 0x12345678u;
    config.name = "Test-DPLS-ZMU";
    config.rng = DPLS_SIM_RNG_SEQUENTIAL;
    config.sequential_start = 0x10u;
    config.emit_tx = emit_frame;

    dpls_sim_board_init(&board, &config);
    phy6252_emu_set_cccd(&board.radio, PHY6252_EMU_CCCD_SAMSUNG);
    dpls_sim_board_connect(&board);

    for (i = 0; i < ZMU_REQUEST_COUNT; ++i) {
        board.now_ms += 10u;
        CHECK(dpls_sim_board_push_rx(
            &board,
            zmu_requests[i].data,
            zmu_requests[i].length));
        dpls_sim_board_run_after_write(&board);
        /* LED scene is applied in the RX turn. Do not wait for the notify-pace
         * ticks first: those advance now_ms and change the remaining period. */
        if (i == 0u) {
            CHECK(dpls_led_tick(&board.led, board.now_ms) == 500u);
            CHECK(board.led_level);
            (void)dpls_led_tick(&board.led, board.now_ms + 499u);
            CHECK(board.led_level);
            (void)dpls_led_tick(&board.led, board.now_ms + 500u);
            CHECK(!board.led_level);
            (void)dpls_led_tick(&board.led, board.now_ms + 1000u);
            CHECK(board.led_level);
            board.now_ms += 1000u;
        }
        if (i == 1u) {
            (void)dpls_led_tick(&board.led, board.now_ms);
            CHECK(!board.led_level);
        }
        /* Samsung CCCD 0x03: PHY6252 advances notify TX on the pace tick, not ATT CFM. */
        while (board.radio.tx.in_flight || board.radio.tx.count != 0u) {
            dpls_sim_board_tick(&board, DPLS_SIM_TX_NOTIFY_PACE_MS);
        }
        if (i == 3u) CHECK(dpls_server_authenticated(&board.server));
        if (i >= 8u && i <= 22u) {
            uint16_t step = (uint16_t)(i - 8u);
            uint16_t mode_index = (uint16_t)(step / 3u);
            uint16_t phase = (uint16_t)(step % 3u);
            if (phase == 0u) CHECK(board.hardware_mode == mode_matrix[mode_index]);
            if (phase == 2u) CHECK(board.hardware_mode == DPLS_MODE_NORMAL);
        }
        if (i == 22u) {
            puts("SCENARIO_MODES_OK");
            fflush(stdout);
        }
        if (i == 26u) {
            CHECK(!dpls_server_authenticated(&board.server));
            puts("SCENARIO_SETTINGS_OK");
            fflush(stdout);
        }
    }

    CHECK(board.response_count == ZMU_REQUEST_COUNT);
    CHECK(board.diagnostic_errors == 0u);
    CHECK(board.hardware_mode == DPLS_MODE_NORMAL);

    dpls_sim_board_disconnect(&board);
    CHECK(board.hardware_mode == DPLS_MODE_NORMAL);

    printf("ZMU_E2E_OK responses=%u events=%u\n", board.response_count, board.event_count);
    return 0;
}

void SystemInit(void) {}

void _start(void)
{
    initialise_monitor_handles();
    exit(main());
}

__attribute__((used)) void _fini(void) {}
