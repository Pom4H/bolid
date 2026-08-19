#include "dpls_phy6252_auth.h"

#include "dpls_phy6252_storage.h"
#include "ll_enc.h"
#include <string.h>
#include <tinycrypt/hmac.h>

bool dpls_phy6252_auth_random_bytes(void *context, uint8_t *out, size_t length)
{
    size_t offset = 0u;
    (void)context;
    while (offset < length) {
        uint8_t chunk = (uint8_t)((length - offset) > 16u ? 16u : (length - offset));
        if (LL_ENC_GenerateTrueRandNum(out + offset, chunk) != SUCCESS) {
            memset(out, 0, length);
            return false;
        }
        offset += chunk;
    }
    return true;
}

bool dpls_phy6252_auth_verify_proof(void *context,
                                    const uint8_t device_nonce[DPLS_AUTH_NONCE_SIZE],
                                    const uint8_t client_nonce[DPLS_AUTH_NONCE_SIZE],
                                    uint32_t session_id,
                                    const uint8_t proof[DPLS_AUTH_PROOF_SIZE])
{
    /* Keep the HMAC state static. The PHY6252 OSAL task stack is 1 KiB and the
     * previous stack-local HMAC state overflowed it under an RX callback. */
    static struct tc_hmac_state_struct hmac;
    uint8_t verifier[DPLS_AUTH_PROOF_SIZE];
    uint8_t signed_data[36];
    uint8_t expected[DPLS_AUTH_PROOF_SIZE];
    uint8_t difference = 0u;
    uint8_t i;
    (void)context;

    if (!dpls_phy6252_storage_copy_verifier(verifier)) return false;
    memcpy(signed_data, device_nonce, DPLS_AUTH_NONCE_SIZE);
    memcpy(signed_data + 16, client_nonce, DPLS_AUTH_NONCE_SIZE);
    signed_data[32] = (uint8_t)session_id;
    signed_data[33] = (uint8_t)(session_id >> 8);
    signed_data[34] = (uint8_t)(session_id >> 16);
    signed_data[35] = (uint8_t)(session_id >> 24);

    if (!tc_hmac_set_key(&hmac, verifier, sizeof(verifier)) ||
        !tc_hmac_init(&hmac) ||
        !tc_hmac_update(&hmac, signed_data, sizeof(signed_data)) ||
        !tc_hmac_final(expected, sizeof(expected), &hmac)) {
        memset(&hmac, 0, sizeof(hmac));
        memset(verifier, 0, sizeof(verifier));
        return false;
    }

    for (i = 0u; i < sizeof(expected); ++i)
        difference |= (uint8_t)(expected[i] ^ proof[i]);

    memset(&hmac, 0, sizeof(hmac));
    memset(verifier, 0, sizeof(verifier));
    memset(expected, 0, sizeof(expected));
    memset(signed_data, 0, sizeof(signed_data));
    return difference == 0u;
}
