#include "dpls_ble_identity.h"

#include "OSAL.h"
#include "gap.h"
#include "hci.h"
#include "ll_enc.h"
#include "osal_snv.h"
#include "peripheral.h"
#include "rom_sym_def.h"
#include "flash.h"
#include <string.h>

#define DPLS_CHIP_MAC_FLASH_ADDR 0x4000u
#define DPLS_BLE_MAC_SNV_ID 0x82u
#define DPLS_BLE_MAC_MAGIC 0x43414D44u /* "DMAC" */

typedef struct {
    uint32_t magic;
    uint8_t addr[B_ADDR_LEN];
} dpls_ble_mac_record_t;

/* PHY6222 LL RAM layout for the public identity address (see bleuart_at_cmd.c). */
#define DPLS_OWN_PUBLIC_ADDR ((volatile uint8_t *)(uintptr_t)0x1fff0965u)

static uint8_t s_identity_mac[B_ADDR_LEN];
static bool s_identity_mac_valid;

static bool buffer_is_fill(const uint8_t *buf, uint8_t value, uint8_t length)
{
    return osal_isbufset((uint8_t *)buf, value, length) == TRUE;
}

static bool mac_is_invalid(const uint8_t *mac)
{
    return buffer_is_fill(mac, 0xFF, B_ADDR_LEN) || buffer_is_fill(mac, 0x00, B_ADDR_LEN);
}

static bool key_is_invalid(const uint8_t *key)
{
    return buffer_is_fill(key, 0x00, KEYLEN) || buffer_is_fill(key, 0xFF, KEYLEN);
}

static void read_chip_mac(uint8_t out[B_ADDR_LEN])
{
    uint32_t addr = DPLS_CHIP_MAC_FLASH_ADDR;
    hal_flash_read(addr++, &out[3], 1);
    hal_flash_read(addr++, &out[2], 1);
    hal_flash_read(addr++, &out[1], 1);
    hal_flash_read(addr++, &out[0], 1);
    hal_flash_read(addr++, &out[5], 1);
    hal_flash_read(addr, &out[4], 1);
}

static bool snv_mac_is_usable(const uint8_t mac[B_ADDR_LEN])
{
    /* Older builds stored static-random addresses; SMP needs public identity. */
    return !mac_is_invalid(mac) && (mac[0] & 0xC0u) != 0xC0u;
}

static void write_chip_mac_flash(const uint8_t mac[B_ADDR_LEN])
{
    uint32_t addr = DPLS_CHIP_MAC_FLASH_ADDR;
    uint8_t byte;
    byte = mac[3]; (void)hal_flash_write(addr++, &byte, 1);
    byte = mac[2]; (void)hal_flash_write(addr++, &byte, 1);
    byte = mac[1]; (void)hal_flash_write(addr++, &byte, 1);
    byte = mac[0]; (void)hal_flash_write(addr++, &byte, 1);
    byte = mac[5]; (void)hal_flash_write(addr++, &byte, 1);
    byte = mac[4]; (void)hal_flash_write(addr, &byte, 1);
}

static bool read_mac_snv(uint8_t out[B_ADDR_LEN])
{
    dpls_ble_mac_record_t record;
    if (osal_snv_read(DPLS_BLE_MAC_SNV_ID, sizeof(record), &record) != SUCCESS) return false;
    if (record.magic != DPLS_BLE_MAC_MAGIC || !snv_mac_is_usable(record.addr)) return false;
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

static bool random_bytes(uint8_t *out, uint8_t length)
{
    return LL_ENC_GenerateTrueRandNum(out, length) == SUCCESS;
}

static bool generate_mac(uint8_t out[B_ADDR_LEN])
{
    uint8_t attempt;
    for (attempt = 0; attempt < 4u; ++attempt) {
        if (!random_bytes(out, B_ADDR_LEN)) return false;
        out[0] &= 0x3Fu;
        if (!mac_is_invalid(out)) return true;
    }
    return false;
}

static void write_own_public_addr(const uint8_t mac[B_ADDR_LEN])
{
    volatile uint8_t *p = DPLS_OWN_PUBLIC_ADDR;
    p[0] = mac[5];
    p[1] = mac[4];
    p[2] = mac[3];
    p[3] = mac[2];
    p[4] = mac[1];
    p[5] = mac[0];
    (void)HCI_ReadBDADDRCmd();
}

static uint8_t identity_addr_type(const uint8_t display_mac[B_ADDR_LEN])
{
    return ((display_mac[0] & 0xC0u) == 0xC0u) ? ADDRTYPE_STATIC : ADDRTYPE_PUBLIC;
}

static bool ensure_mac(uint8_t mac[B_ADDR_LEN])
{
    bool chip_invalid;
    read_chip_mac(mac);
    chip_invalid = mac_is_invalid(mac);
    if (!chip_invalid) {
        write_own_public_addr(mac);
        return true;
    }
    if (read_mac_snv(mac)) {
        if (chip_invalid) write_chip_mac_flash(mac);
        write_own_public_addr(mac);
        return true;
    }
    if (!generate_mac(mac) || !write_mac_snv(mac)) return false;
    write_chip_mac_flash(mac);
    write_own_public_addr(mac);
    return true;
}

static bool read_key_snv(uint16_t snv_id, uint8_t out[KEYLEN])
{
    if (osal_snv_read(snv_id, KEYLEN, out) != SUCCESS) return false;
    return !key_is_invalid(out);
}

static bool write_key_snv(uint16_t snv_id, const uint8_t key[KEYLEN])
{
    return osal_snv_write(snv_id, KEYLEN, (void *)key) == SUCCESS;
}

static bool ensure_identity_keys(uint8_t irk[KEYLEN], uint8_t srk[KEYLEN])
{
    if (read_key_snv(BLE_NVID_IRK, irk) && read_key_snv(BLE_NVID_CSRK, srk)) return true;
    if (!random_bytes(irk, KEYLEN) || !random_bytes(srk, KEYLEN)) return false;
    if (!write_key_snv(BLE_NVID_IRK, irk) || !write_key_snv(BLE_NVID_CSRK, srk)) return false;
    return true;
}

void dpls_ble_identity_prepare(void)
{
    uint8_t mac[B_ADDR_LEN];
    uint8_t irk[KEYLEN];
    uint8_t srk[KEYLEN];
    if (!ensure_mac(mac) || !ensure_identity_keys(irk, srk)) return;
    memcpy(s_identity_mac, mac, B_ADDR_LEN);
    s_identity_mac_valid = true;
    (void)GAPRole_SetParameter(GAPROLE_IRK, KEYLEN, irk);
    (void)GAPRole_SetParameter(GAPROLE_SRK, KEYLEN, srk);
}

void dpls_ble_identity_on_stack_started(void)
{
    uint8_t irk[KEYLEN];
    uint8_t hci_addr[B_ADDR_LEN];
    uint8_t zero_irk[KEYLEN];
    uint8_t addr_type;

    if (!s_identity_mac_valid) return;
    GAPRole_GetParameter(GAPROLE_IRK, irk);
    if (key_is_invalid(irk)) return;

    addr_type = identity_addr_type(s_identity_mac);
    (void)GAP_ConfigDeviceAddr(addr_type, s_identity_mac);

    GAPRole_GetParameter(GAPROLE_BD_ADDR, hci_addr);
    if (mac_is_invalid(hci_addr)) return;
    memset(zero_irk, 0, sizeof(zero_irk));
    (void)HCI_LE_AddDevToResolvingListCmd(addr_type, hci_addr, zero_irk, irk);
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
    return (uint32_t)s_identity_mac[0] | ((uint32_t)s_identity_mac[1] << 8) |
           ((uint32_t)s_identity_mac[2] << 16) | ((uint32_t)s_identity_mac[3] << 24);
}
