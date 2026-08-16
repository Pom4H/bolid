#ifndef DPLS_HMAC_H
#define DPLS_HMAC_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* HMAC-SHA256 / PBKDF2 matching the phone (DplsCrypto) and PHY TinyCrypt. */
bool dpls_hmac_sha256(const uint8_t *key, size_t key_length,
                      const uint8_t *message, size_t message_length,
                      uint8_t out[32]);
bool dpls_pbkdf2_hmac_sha256(const uint8_t *password, size_t password_length,
                             const uint8_t *salt, size_t salt_length,
                             uint32_t iterations, uint8_t *out, size_t out_length);

#endif
