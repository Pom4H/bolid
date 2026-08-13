#include "dpls_protocol.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static void test_crc_check_vector(void) {
    const uint8_t data[] = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
    assert(dpls_crc16(data, sizeof(data)) == 0x29b1u);
    assert(dpls_crc16(data, 0) == 0xffffu);
}

static void test_round_trip(void) {
    dpls_frame_t in, out;
    uint8_t buf[DPLS_MAX_FRAME];
    size_t n;
    uint8_t payload[] = {0x11, 0x22, 0x33, 0x44, 0x55};

    memset(&in, 0, sizeof(in));
    in.type = DPLS_MSG_MODE_SET;
    in.flags = 0x07;
    in.sequence = 0x1234;
    in.payload_length = (uint16_t)sizeof(payload);
    memcpy(in.payload, payload, sizeof(payload));

    n = dpls_frame_encode(&in, buf, sizeof(buf));
    assert(n == DPLS_PROTOCOL_OVERHEAD + sizeof(payload));
    assert(buf[0] == DPLS_PROTOCOL_VERSION);
    assert(buf[1] == DPLS_MSG_MODE_SET);
    assert(buf[2] == 0x07);

    assert(dpls_frame_decode(buf, n, &out));
    assert(out.type == in.type && out.flags == in.flags && out.sequence == in.sequence);
    assert(out.payload_length == in.payload_length);
    assert(memcmp(out.payload, payload, sizeof(payload)) == 0);
}

static void test_empty_payload(void) {
    dpls_frame_t in, out;
    uint8_t buf[DPLS_MAX_FRAME];
    size_t n;

    memset(&in, 0, sizeof(in));
    in.type = DPLS_MSG_KEEP_ALIVE;
    n = dpls_frame_encode(&in, buf, sizeof(buf));
    assert(n == DPLS_PROTOCOL_OVERHEAD);
    assert(dpls_frame_decode(buf, n, &out));
    assert(out.type == DPLS_MSG_KEEP_ALIVE && out.payload_length == 0);
}

static void test_encode_rejects(void) {
    dpls_frame_t in;
    uint8_t buf[DPLS_MAX_FRAME];

    memset(&in, 0, sizeof(in));
    assert(dpls_frame_encode(NULL, buf, sizeof(buf)) == 0);
    assert(dpls_frame_encode(&in, NULL, sizeof(buf)) == 0);
    in.payload_length = DPLS_MAX_PAYLOAD + 1u;
    assert(dpls_frame_encode(&in, buf, sizeof(buf)) == 0);
    in.payload_length = 8;
    assert(dpls_frame_encode(&in, buf, DPLS_PROTOCOL_OVERHEAD + 7u) == 0);
}

static void test_decode_rejects(void) {
    dpls_frame_t in, out;
    uint8_t buf[DPLS_MAX_FRAME];
    size_t n;

    memset(&in, 0, sizeof(in));
    in.type = DPLS_MSG_STATE_GET;
    in.payload_length = 3;
    in.payload[0] = 1; in.payload[1] = 2; in.payload[2] = 3;
    n = dpls_frame_encode(&in, buf, sizeof(buf));

    assert(!dpls_frame_decode(NULL, n, &out));
    assert(!dpls_frame_decode(buf, n, NULL));
    assert(!dpls_frame_decode(buf, DPLS_PROTOCOL_OVERHEAD - 1u, &out));
    buf[0] = 0x7f;
    assert(!dpls_frame_decode(buf, n, &out));
    buf[0] = DPLS_PROTOCOL_VERSION;
    buf[n - 1u] = (uint8_t)(buf[n - 1u] + 1u);
    assert(!dpls_frame_decode(buf, n, &out));
    n = dpls_frame_encode(&in, buf, sizeof(buf));
    assert(!dpls_frame_decode(buf, n - 1u, &out));
    /* Declared payload length larger than DPLS_MAX_PAYLOAD. */
    buf[5] = 0xff; buf[6] = 0x00;
    assert(!dpls_frame_decode(buf, DPLS_PROTOCOL_OVERHEAD + 0xffu, &out));
}

int main(void) {
    test_crc_check_vector();
    test_round_trip();
    test_empty_payload();
    test_encode_rejects();
    test_decode_rejects();
    puts("test_protocol: OK");
    return 0;
}
