#include "dpls_hmac.h"
#include "dpls_protocol.h"
#include "dpls_sim_board.h"

#include <assert.h>
#include <string.h>

static uint8_t captured[4][DPLS_MAX_FRAME];
static size_t captured_len[4];
static unsigned captured_count;

static void emit(void *context, const uint8_t *frame, size_t length)
{
    (void)context;
    assert(captured_count < 4u);
    assert(length <= DPLS_MAX_FRAME);
    memcpy(captured[captured_count], frame, length);
    captured_len[captured_count] = length;
    ++captured_count;
}

static size_t encode_request(uint8_t type, uint16_t sequence, const uint8_t *payload,
                             uint16_t payload_length, uint8_t *out)
{
    dpls_frame_t frame;
    memset(&frame, 0, sizeof(frame));
    frame.type = type;
    frame.flags = DPLS_FLAG_REQUEST;
    frame.sequence = sequence;
    frame.payload_length = payload_length;
    if (payload_length != 0u) memcpy(frame.payload, payload, payload_length);
    return dpls_frame_encode(&frame, out, DPLS_MAX_FRAME);
}

int main(void)
{
    dpls_sim_board_t board;
    dpls_sim_board_config_t config;
    uint8_t hello[DPLS_MAX_FRAME];
    uint8_t proof_frame[DPLS_MAX_FRAME];
    uint8_t client_nonce[16];
    uint8_t proof[48];
    uint8_t signed_data[36];
    uint8_t mac[32];
    dpls_frame_t challenge;
    size_t n;
    unsigned i;

    memset(&config, 0, sizeof(config));
    config.device_id = 0x12345678u;
    config.name = "Test-DPLS-ZMU";
    config.rng = DPLS_SIM_RNG_SEQUENTIAL;
    config.sequential_start = 0x10u;
    config.emit_tx = emit;

    dpls_sim_board_init(&board, &config);
    dpls_sim_board_connect(&board);

    for (i = 0; i < 16u; ++i) client_nonce[i] = (uint8_t)(0xA0u + i);
    n = encode_request(DPLS_MSG_HELLO, 0x1003u, client_nonce, 16u, hello);
    assert(dpls_sim_board_push_rx(&board, hello, n));
    dpls_sim_board_run_after_write(&board);
    assert(captured_count == 1u);
    assert(board.radio.tx.in_flight);
    assert(dpls_frame_decode(captured[0], captured_len[0], &challenge));
    assert(challenge.type == DPLS_MSG_AUTH_CHALLENGE);

    memcpy(signed_data, challenge.payload + 4, 16);
    memcpy(signed_data + 16, client_nonce, 16);
    signed_data[32] = challenge.payload[0];
    signed_data[33] = challenge.payload[1];
    signed_data[34] = challenge.payload[2];
    signed_data[35] = challenge.payload[3];
    assert(dpls_hmac_sha256(board.verifier, DPLS_AUTH_PROOF_SIZE, signed_data, sizeof(signed_data), mac));
    memcpy(proof, client_nonce, 16);
    memcpy(proof + 16, mac, 32);
    n = encode_request(DPLS_MSG_AUTH_PROOF, 0x1004u, proof, 48u, proof_frame);
    assert(dpls_sim_board_push_rx(&board, proof_frame, n));
    dpls_sim_board_run_after_write(&board);
    /* AUTH_RESULT stays behind the in-flight AUTH_CHALLENGE until ATT CFM,
     * same as PHY6252 GATT_Indication. */
    assert(captured_count == 1u);
    assert(board.radio.tx.count >= 1u);

    dpls_sim_board_tx_confirmed(&board);
    assert(captured_count == 2u);
    assert(dpls_server_authenticated(&board.server));

    captured_count = 0;
    dpls_sim_board_disconnect(&board);
    dpls_sim_board_init(&board, &config);
    dpls_sim_board_connect(&board);
    n = encode_request(DPLS_MSG_HELLO, 1u, client_nonce, 16u, hello);
    assert(dpls_sim_board_push_rx(&board, hello, n));
    dpls_sim_board_run_after_write(&board);
    assert(captured_count == 1u);
    n = encode_request(DPLS_MSG_AUTH_PROOF, 2u, proof, 48u, proof_frame);
    assert(dpls_sim_board_push_rx(&board, proof_frame, n));
    dpls_sim_board_run_after_write(&board);
    assert(captured_count == 1u);
    captured_count = 0;
    dpls_sim_board_disconnect(&board);
    dpls_sim_board_init(&board, &config);
    phy6252_emu_set_cccd(&board.radio, PHY6252_EMU_CCCD_SAMSUNG);
    dpls_sim_board_connect(&board);
    n = encode_request(DPLS_MSG_HELLO, 3u, client_nonce, 16u, hello);
    assert(dpls_sim_board_push_rx(&board, hello, n));
    dpls_sim_board_run_after_write(&board);
    assert(captured_count == 1u);
    n = encode_request(DPLS_MSG_AUTH_PROOF, 4u, proof, 48u, proof_frame);
    assert(dpls_sim_board_push_rx(&board, proof_frame, n));
    dpls_sim_board_run_after_write(&board);
    /* Samsung CCCD 0x03: no ATT CFM, but PHY6252 still keeps one PDU in flight
     * until the notify pace tick. AUTH_RESULT stays queued behind the challenge. */
    assert(captured_count == 1u);
    assert(board.radio.tx.in_flight);
    dpls_sim_board_tick(&board, DPLS_SIM_TX_NOTIFY_PACE_MS);
    assert(captured_count == 2u);
    assert(dpls_server_authenticated(&board.server));
    return 0;
}
