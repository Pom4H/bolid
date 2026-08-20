#include "dpls_protocol.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

/*
 * Deterministic protocol fuzzer for CI.
 *
 * This intentionally uses the production encoder/decoder. Under ASan+UBSan it
 * turns arbitrary wire input into a cheap memory-safety gate without needing a
 * separate fuzzing service. Keep the seed fixed so failures are reproducible.
 */
static uint32_t rng_state = 0x6d2b79f5u;

static uint32_t rnd32(void)
{
    uint32_t x = rng_state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    rng_state = x;
    return x;
}

static uint8_t rnd8(void)
{
    return (uint8_t)rnd32();
}

static void fill_random(uint8_t *data, size_t length)
{
    size_t i;
    for (i = 0u; i < length; ++i) data[i] = rnd8();
}

static void assert_frame_equal(const dpls_frame_t *a, const dpls_frame_t *b)
{
    assert(a->type == b->type);
    assert(a->flags == b->flags);
    assert(a->sequence == b->sequence);
    assert(a->payload_length == b->payload_length);
    assert(memcmp(a->payload, b->payload, a->payload_length) == 0);
}

static void fuzz_arbitrary_wire_bytes(void)
{
    uint8_t input[DPLS_MAX_FRAME + 64u];
    uint8_t encoded[DPLS_MAX_FRAME];
    dpls_frame_t decoded;
    dpls_frame_t decoded_again;
    size_t iteration;

    for (iteration = 0u; iteration < 250000u; ++iteration) {
        size_t length = (size_t)(rnd32() % sizeof(input));
        fill_random(input, length);

        if (!dpls_frame_decode(input, length, &decoded)) continue;

        assert(decoded.payload_length <= DPLS_MAX_PAYLOAD);
        {
            size_t n = dpls_frame_encode(&decoded, encoded, sizeof(encoded));
            assert(n >= DPLS_PROTOCOL_OVERHEAD && n <= DPLS_MAX_FRAME);
            assert(dpls_frame_decode(encoded, n, &decoded_again));
            assert_frame_equal(&decoded, &decoded_again);
        }
    }
}

static void fuzz_valid_round_trips_and_single_bit_corruption(void)
{
    uint8_t encoded[DPLS_MAX_FRAME];
    uint8_t corrupt[DPLS_MAX_FRAME];
    dpls_frame_t in;
    dpls_frame_t out;
    size_t iteration;

    for (iteration = 0u; iteration < 50000u; ++iteration) {
        size_t n;
        size_t byte_index;
        uint8_t bit;
        uint16_t i;

        memset(&in, 0, sizeof(in));
        in.type = rnd8();
        in.flags = (uint8_t)(rnd8() & 0x0fu);
        in.sequence = (uint16_t)rnd32();
        in.payload_length = (uint16_t)(rnd32() % (DPLS_MAX_PAYLOAD + 1u));
        for (i = 0u; i < in.payload_length; ++i) in.payload[i] = rnd8();

        n = dpls_frame_encode(&in, encoded, sizeof(encoded));
        assert(n == DPLS_PROTOCOL_OVERHEAD + in.payload_length);
        assert(dpls_frame_decode(encoded, n, &out));
        assert_frame_equal(&in, &out);

        /* CRC-16/CCITT catches every single-bit error in a protected frame. */
        memcpy(corrupt, encoded, n);
        byte_index = (size_t)(rnd32() % n);
        bit = (uint8_t)(1u << (rnd32() & 7u));
        corrupt[byte_index] ^= bit;
        assert(!dpls_frame_decode(corrupt, n, &out));
    }
}

static void fuzz_all_lengths_around_boundaries(void)
{
    uint8_t input[DPLS_MAX_FRAME + 2u];
    dpls_frame_t out;
    size_t length;

    fill_random(input, sizeof(input));
    for (length = 0u; length <= sizeof(input); ++length) {
        (void)dpls_frame_decode(input, length, &out);
    }
}

int main(void)
{
    fuzz_arbitrary_wire_bytes();
    fuzz_valid_round_trips_and_single_bit_corruption();
    fuzz_all_lengths_around_boundaries();
    puts("protocol deterministic fuzz: OK (300k+ cases)");
    return 0;
}
