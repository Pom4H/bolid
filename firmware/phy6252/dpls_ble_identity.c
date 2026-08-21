#include "dpls_ble_identity.h"

#include "OSAL.h"
#include "hci.h"
#include "ll_enc.h"
#include "osal_snv.h"
#include "peripheral.h"
#include <string.h>

#define DPLS_BLE_MAC_SNV_ID 0x82u
#define DPLS_BLE_MAC_MAGIC 0x43414D44u /* "DMAC" */

typedef struct {
    uint32_t magic;
    uint8_t addr[B_ADDR_LEN];
} dpls_ble_mac_record_t;

static uint8_t s_identity_mac[B_ADDR_LEN];
static bool s_identity_mac_valid;

static bool mac_is_invalid(const uint8_t *mac)
{
    uint8_t i;
    bool zero = true;
    bool ff = true;
    for (i = 0; i < B_ADDR_LEN; ++i) {
        zero &= mac[i] == 0;
        ff &= mac[i] == 0xFF;
    }
    return zero || ff;
}

static bool read_mac_snv(uint8_t out[B_ADDR_LEN])
{
    dpls_ble_mac_record_t record;
    if (osal_snv_read(DPLS_BLE_MAC_SNV_ID, sizeof(record), &record) != SUCCESS) return false;
    if (record.magic != DPLS_BLE_MAC_MAGIC || mac_is_invalid(record.addr)) return false;
    memcpy(out, record.addr, B_ADDR_LEN);
    return true;
}

static bool write_mac_snv(const uint8_t mac[B_ADDR_LEN])
{
    dpls_ble_mac_record_t record;
    record.magic = DPLS_BLE_MAC_MAGIC;
    memcpy(record.addr, mac, B_ADDR_LEN);
    return osal_snv_write(DPLS_BLE_MAC_SNV_ID, sizeof(record), &record) == SUCCESS;
}

static bool generate_mac(uint8_t out[B_ADDR_LEN])
{
    if (LL_ENC_GenerateTrueRandNum(out, B_ADDR_LEN) != SUCCESS) return false;
    out[0] &= 0x3Fu;
    return !mac_is_invalid(out);
}

static bool set_controller_addr(const uint8_t mac[B_ADDR_LEN])
{
    uint8_t addr[B_ADDR_LEN];
    uint8_t i;

    for (i = 0; i < B_ADDR_LEN; ++i) {
        addr[i] = mac[B_ADDR_LEN - 1u - i];
    }

    return HCI_EXT_SetBDADDRCmd(addr) == HCI_SUCCESS;
}

static bool select_mac(uint8_t mac[B_ADDR_LEN])
{
    if (read_mac_snv(mac)) return true;
    return generate_mac(mac);
}

void dpls_ble_identity_prepare(void)
{
    uint8_t mac[B_ADDR_LEN];

    /* В раннем boot только RAM и controller setup.
     * Никаких записей flash и обязательных ключей. */
    if (!select_mac(mac)) return;
    if (!set_controller_addr(mac)) return;

    memcpy(s_identity_mac, mac, B_ADDR_LEN);
    s_identity_mac_valid = true;
}

void dpls_ble_identity_on_stack_started(void)
{
    if (!s_identity_mac_valid) return;

    /* Persistence выполняется после запуска BLE stack. */
    (void)write_mac_snv(s_identity_mac);
}

void dpls_ble_identity_reset_bonding_keys(void)
{
    uint8_t erased[KEYLEN];
    memset(erased, 0xFF, sizeof(erased));
    (void)osal_snv_write(BLE_NVID_IRK, KEYLEN, erased);
    (void)osal_snv_write(BLE_NVID_CSRK, KEYLEN, erased);
}

uint32_t dpls_ble_identity_device_id(void)
{
    if (!s_identity_mac_valid) return 0u;
    return (uint32_t)s_identity_mac[0] |
           ((uint32_t)s_identity_mac[1] << 8) |
           ((uint32_t)s_identity_mac[2] << 16) |
           ((uint32_t)s_identity_mac[3] << 24);
}
