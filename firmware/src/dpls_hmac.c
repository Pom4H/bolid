#include "dpls_hmac.h"

#include <string.h>

#define SHA256_BLOCK 64u
#define SHA256_LEN 32u
#define HMAC_MAX_MESSAGE 256u

static uint32_t rotr(uint32_t value, uint32_t bits)
{
    return (value >> bits) | (value << (32u - bits));
}

static uint32_t load_be32(const uint8_t *p)
{
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static void store_be32(uint8_t *p, uint32_t value)
{
    p[0] = (uint8_t)(value >> 24);
    p[1] = (uint8_t)(value >> 16);
    p[2] = (uint8_t)(value >> 8);
    p[3] = (uint8_t)value;
}

static void sha256_compress(uint32_t h[8], const uint8_t block[SHA256_BLOCK])
{
    static const uint32_t k[64] = {
        0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
        0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
        0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
        0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
        0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
        0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
        0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
        0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
    };
    uint32_t w[64];
    uint32_t a, b, c, d, e, f, g, hh;
    unsigned i;
    for (i = 0; i < 16u; ++i) w[i] = load_be32(block + i * 4u);
    for (i = 16u; i < 64u; ++i) {
        uint32_t s0 = rotr(w[i - 15u], 7u) ^ rotr(w[i - 15u], 18u) ^ (w[i - 15u] >> 3);
        uint32_t s1 = rotr(w[i - 2u], 17u) ^ rotr(w[i - 2u], 19u) ^ (w[i - 2u] >> 10);
        w[i] = w[i - 16u] + s0 + w[i - 7u] + s1;
    }
    a = h[0]; b = h[1]; c = h[2]; d = h[3];
    e = h[4]; f = h[5]; g = h[6]; hh = h[7];
    for (i = 0; i < 64u; ++i) {
        uint32_t s1 = rotr(e, 6u) ^ rotr(e, 11u) ^ rotr(e, 25u);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t temp1 = hh + s1 + ch + k[i] + w[i];
        uint32_t s0 = rotr(a, 2u) ^ rotr(a, 13u) ^ rotr(a, 22u);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = s0 + maj;
        hh = g; g = f; f = e; e = d + temp1;
        d = c; c = b; b = a; a = temp1 + temp2;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d;
    h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    memset(w, 0, sizeof(w));
}

static void sha256(const uint8_t *input, size_t length, uint8_t out[SHA256_LEN])
{
    uint32_t h[8] = {
        0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u,
    };
    uint8_t block[SHA256_BLOCK];
    uint64_t bit_length = (uint64_t)length * 8u;
    size_t offset = 0;
    size_t remain;
    unsigned i;

    while (offset + SHA256_BLOCK <= length) {
        sha256_compress(h, input + offset);
        offset += SHA256_BLOCK;
    }
    remain = length - offset;
    memset(block, 0, sizeof(block));
    memcpy(block, input + offset, remain);
    block[remain] = 0x80u;
    if (remain >= 56u) {
        sha256_compress(h, block);
        memset(block, 0, sizeof(block));
    }
    store_be32(block + 56, (uint32_t)(bit_length >> 32));
    store_be32(block + 60, (uint32_t)bit_length);
    sha256_compress(h, block);
    for (i = 0; i < 8u; ++i) store_be32(out + i * 4u, h[i]);
    memset(block, 0, sizeof(block));
}

bool dpls_hmac_sha256(const uint8_t *key, size_t key_length,
                      const uint8_t *message, size_t message_length,
                      uint8_t out[32])
{
    uint8_t block[SHA256_BLOCK];
    uint8_t inner_pad[SHA256_BLOCK];
    uint8_t outer_pad[SHA256_BLOCK];
    uint8_t inner[SHA256_LEN];
    uint8_t hashed_key[SHA256_LEN];
    uint8_t work[SHA256_BLOCK + HMAC_MAX_MESSAGE];
    size_t i;

    if (key == NULL || message == NULL || out == NULL) return false;
    if (message_length > HMAC_MAX_MESSAGE) return false;
    memset(block, 0, sizeof(block));
    if (key_length > SHA256_BLOCK) {
        sha256(key, key_length, hashed_key);
        memcpy(block, hashed_key, SHA256_LEN);
        memset(hashed_key, 0, sizeof(hashed_key));
    } else {
        memcpy(block, key, key_length);
    }
    for (i = 0; i < SHA256_BLOCK; ++i) {
        inner_pad[i] = (uint8_t)(block[i] ^ 0x36u);
        outer_pad[i] = (uint8_t)(block[i] ^ 0x5cu);
    }
    memcpy(work, inner_pad, SHA256_BLOCK);
    memcpy(work + SHA256_BLOCK, message, message_length);
    sha256(work, SHA256_BLOCK + message_length, inner);
    memcpy(work, outer_pad, SHA256_BLOCK);
    memcpy(work + SHA256_BLOCK, inner, SHA256_LEN);
    sha256(work, SHA256_BLOCK + SHA256_LEN, out);
    memset(block, 0, sizeof(block));
    memset(inner_pad, 0, sizeof(inner_pad));
    memset(outer_pad, 0, sizeof(outer_pad));
    memset(inner, 0, sizeof(inner));
    memset(work, 0, sizeof(work));
    return true;
}

bool dpls_pbkdf2_hmac_sha256(const uint8_t *password, size_t password_length,
                             const uint8_t *salt, size_t salt_length,
                             uint32_t iterations, uint8_t *out, size_t out_length)
{
    uint32_t block_index;
    size_t offset = 0;
    uint8_t salt_block[HMAC_MAX_MESSAGE];

    if (password == NULL || salt == NULL || out == NULL ||
        iterations == 0u || out_length == 0u || salt_length + 4u > HMAC_MAX_MESSAGE) {
        return false;
    }
    for (block_index = 1u; offset < out_length; ++block_index) {
        uint8_t u[SHA256_LEN];
        uint8_t t[SHA256_LEN];
        uint32_t round;
        size_t count;
        size_t i;
        memcpy(salt_block, salt, salt_length);
        store_be32(salt_block + salt_length, block_index);
        if (!dpls_hmac_sha256(password, password_length, salt_block, salt_length + 4u, u)) {
            return false;
        }
        memcpy(t, u, SHA256_LEN);
        for (round = 1u; round < iterations; ++round) {
            uint8_t next[SHA256_LEN];
            if (!dpls_hmac_sha256(password, password_length, u, SHA256_LEN, next)) return false;
            memcpy(u, next, SHA256_LEN);
            for (i = 0; i < SHA256_LEN; ++i) t[i] ^= u[i];
            memset(next, 0, sizeof(next));
        }
        count = out_length - offset;
        if (count > SHA256_LEN) count = SHA256_LEN;
        memcpy(out + offset, t, count);
        offset += count;
        memset(u, 0, sizeof(u));
        memset(t, 0, sizeof(t));
    }
    memset(salt_block, 0, sizeof(salt_block));
    return true;
}
