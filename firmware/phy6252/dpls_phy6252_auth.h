#ifndef DPLS_PHY6252_AUTH_H
#define DPLS_PHY6252_AUTH_H

#include "dpls_server.h"
#include <stddef.h>

bool dpls_phy6252_auth_random_bytes(void *context, uint8_t *out, size_t length);
bool dpls_phy6252_auth_verify_proof(void *context,
                                    const uint8_t device_nonce[DPLS_AUTH_NONCE_SIZE],
                                    const uint8_t client_nonce[DPLS_AUTH_NONCE_SIZE],
                                    uint32_t session_id,
                                    const uint8_t proof[DPLS_AUTH_PROOF_SIZE]);

#endif
