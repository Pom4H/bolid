#include "dpls_ble_identity.h"

#include "dpls_protocol.h"
#include "OSAL.h"
#include "flash.h"
#include "gap.h"
#include "hci.h"
#include "ll_enc.h"
#include "osal_snv.h"
#include "peripheral.h"
#include <string.h>

/* 0x82 was used by pre-factory-data builds. It is read only for migration of
 * development boards; new firmware never creates a random public-looking MAC. */
#define DPLS_LEGACY_BLE_MAC_SNV_ID 0x82u
#define DPLS_LEGACY_BLE_MAC_MAGIC 0x43414D44u /* "DMAC" */

#define DPLS_FACTORY_MAGIC 0x31444944u /* bytes: "DID1" */
#define DPLS_FACTORY_VERSION 1u
#define DPLS_FACTORY_FLAG_BLE_STATIC 0x0001u
#define DPLS_FACTORY_FLAG_IRK 0x0002u
#define DPLS_FACTORY_FLAG_CSRK 0x0004u
#define DPLS_FACTORY_BLE_ADDR_CHIP_PUBLIC 0u
#define DPLS_FACTORY_BLE_ADDR_STATIC 1u

#define DPLS_FACTORY_OFF_MAGIC 0u
#define DPLS_FACTORY_OFF_VERSION 4u
#define DPLS_FACTORY_OFF_LENGTH 6u
#define DPLS_FACTORY_OFF_SERIAL 8u
#define DPLS_FACTORY_OFF_HW_REVISION 12u
#define DPLS_FACTORY_OFF_FLAGS 14u
#define DPLS_FACTORY_OFF_BLE_ADDR 16u
#define DPLS_FACTORY_OFF_BLE_ADDR_TYPE 22u
#define DPLS_FACTORY_OFF_IRK 24u
#define DPLS_FACTORY_OFF_CSRK 40u
#define DPLS_FACTORY_OFF_CRC 62u

typedef struct {
    uint32_t magic;
    uint8_t addr[B_ADDR_LEN];
} dpls_legacy_ble_mac_record_t;

typedef struct {
    uint32_t serial_number;
    uint16_t hardware_revision;
    uint16_t flags;
    uint8_t ble_addr[B_ADDR_LEN];
    uint8_t ble_addr_type;
    uint8_t irk[KEYLEN];
    uint8_t csrk[KEYLEN];
} dpls_factory_identity_t;

/* PHY62XX SDK 3.1.2 defines this object in flash.c but omits the extern from
 * flash.h. Use the vendor decoder/state instead of depending on its raw format. */
extern chipMAddr_t g_chipMAddr;

static uint8_t s_identity_mac[B_ADDR_LEN];
static uint8_t s_identity_addr_type = ADDRTYPE_PUBLIC;
static uint32_t s_device_id;
static bool s_identity_mac_valid;
static bool s_factory_provisioned;
static bool s_factory_keys_present;

static uint16_t rd16(const uint8_t *p)
{
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static uint32_t rd32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

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

static bool factory_identity_load(dpls_factory_identity_t *out)
{
    uint8_t raw[DPLS_FACTORY_IDENTITY_RECORD_SIZE];
    uint16_t stored_crc;

    if (hal_flash_read(DPLS_FACTORY_IDENTITY_FLASH_ADDR, raw, sizeof(raw)) != 0) return false;
    if (rd32(raw + DPLS_FACTORY_OFF_MAGIC) != DPLS_FACTORY_MAGIC) return false;
    if (rd16(raw + DPLS_FACTORY_OFF_VERSION) != DPLS_FACTORY_VERSION) return false;
    if (rd16(raw + DPLS_FACTORY_OFF_LENGTH) != DPLS_FACTORY_IDENTITY_RECORD_SIZE) return false;
    stored_crc = rd16(raw + DPLS_FACTORY_OFF_CRC);
    if (stored_crc != dpls_crc16(raw, DPLS_FACTORY_OFF_CRC)) return false;

    out->serial_number = rd32(raw + DPLS_FACTORY_OFF_SERIAL);
    if (out->serial_number == 0u || out->serial_number == 0xffffffffu) return false;
    out->hardware_revision = rd16(raw + DPLS_FACTORY_OFF_HW_REVISION);
    out->flags = rd16(raw + DPLS_FACTORY_OFF_FLAGS);
    memcpy(out->ble_addr, raw + DPLS_FACTORY_OFF_BLE_ADDR, B_ADDR_LEN);
    out->ble_addr_type = raw[DPLS_FACTORY_OFF_BLE_ADDR_TYPE];
    memcpy(out->irk, raw + DPLS_FACTORY_OFF_IRK, KEYLEN);
    memcpy(out->csrk, raw + DPLS_FACTORY_OFF_CSRK, KEYLEN);

    if ((out->flags & DPLS_FACTORY_FLAG_BLE_STATIC) != 0u) {
        if (out->ble_addr_type != DPLS_FACTORY_BLE_ADDR_STATIC || mac_is_invalid(out->ble_addr) ||
            (out->ble_addr[0] & 0xC0u) != 0xC0u) return false;
    } else if (out->ble_addr_type != DPLS_FACTORY_BLE_ADDR_CHIP_PUBLIC) {
        return false;
    }
    if ((out->flags & DPLS_FACTORY_FLAG_IRK) != 0u && key_is_invalid(out->irk)) return false;
    if ((out->flags & DPLS_FACTORY_FLAG_CSRK) != 0u && key_is_invalid(out->csrk)) return false;
    return true;
}

static bool read_chip_factory_mac(uint8_t out[B_ADDR_LEN])
{
    uint8_t i;

    check_chip_mAddr();
    if (g_chipMAddr.chipMAddrStatus != CHIP_ID_VALID) return false;

    /* flash.c decodes the programmed words into controller B_ADDR byte order.
     * DPLS keeps the identity in human/display order, so reverse it here. */
    for (i = 0; i < B_ADDR_LEN; ++i) {
        out[i] = g_chipMAddr.mAddr[B_ADDR_LEN - 1u - i];
    }
    return !mac_is_invalid(out);
}

static bool legacy_snv_mac_is_usable(const uint8_t mac[B_ADDR_LEN])
{
    /* The legacy slot is accepted only if it looks like a public address.
     * Static-random identity belongs in the CRC-protected factory record. */
    return !mac_is_invalid(mac) && (mac[0] & 0xC0u) != 0xC0u;
}

static bool read_legacy_mac_snv(uint8_t out[B_ADDR_LEN])
{
    dpls_legacy_ble_mac_record_t record;
    if (osal_snv_read(DPLS_LEGACY_BLE_MAC_SNV_ID, sizeof(record), &record) != SUCCESS) return false;
    if (record.magic != DPLS_LEGACY_BLE_MAC_MAGIC || !legacy_snv_mac_is_usable(record.addr)) return false;
    memcpy(out, record.addr, B_ADDR_LEN);
    return true;
}

static bool random_bytes(uint8_t *out, uint8_t length)
{
    return LL_ENC_GenerateTrueRandNum(out, length) == SUCCESS;
}

static bool set_controller_public_addr(const uint8_t mac[B_ADDR_LEN])
{
    uint8_t controller_addr[B_ADDR_LEN];
    uint8_t i;

    for (i = 0; i < B_ADDR_LEN; ++i) {
        controller_addr[i] = mac[B_ADDR_LEN - 1u - i];
    }

    if (HCI_EXT_SetBDADDRCmd(controller_addr) != HCI_SUCCESS) return false;
    (void)HCI_ReadBDADDRCmd();
    return true;
}

static bool select_identity_address(const dpls_factory_identity_t *factory,
                                    bool have_factory,
                                    uint8_t mac[B_ADDR_LEN],
                                    uint8_t *addr_type)
{
    if (have_factory && (factory->flags & DPLS_FACTORY_FLAG_BLE_STATIC) != 0u) {
        memcpy(mac, factory->ble_addr, B_ADDR_LEN);
        *addr_type = ADDRTYPE_STATIC;
        return true;
    }

    if (read_chip_factory_mac(mac)) {
        *addr_type = ADDRTYPE_PUBLIC;
        return set_controller_public_addr(mac);
    }

    /* Migration path for already-flashed prototypes only. No address is ever
     * generated here: a new series unit without a usable chip MAC must be
     * provisioned with a static-random address in the factory record. */
    if (!have_factory && read_legacy_mac_snv(mac)) {
        *addr_type = ADDRTYPE_PUBLIC;
        return set_controller_public_addr(mac);
    }
    return false;
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

static bool ensure_identity_keys(const dpls_factory_identity_t *factory,
                                 bool have_factory,
                                 uint8_t irk[KEYLEN], uint8_t csrk[KEYLEN])
{
    if (have_factory &&
        (factory->flags & (DPLS_FACTORY_FLAG_IRK | DPLS_FACTORY_FLAG_CSRK)) ==
            (DPLS_FACTORY_FLAG_IRK | DPLS_FACTORY_FLAG_CSRK)) {
        memcpy(irk, factory->irk, KEYLEN);
        memcpy(csrk, factory->csrk, KEYLEN);
        s_factory_keys_present = true;
        return true;
    }

    if (read_key_snv(BLE_NVID_IRK, irk) && read_key_snv(BLE_NVID_CSRK, csrk)) return true;
    if (!random_bytes(irk, KEYLEN) || !random_bytes(csrk, KEYLEN)) return false;
    if (!write_key_snv(BLE_NVID_IRK, irk) || !write_key_snv(BLE_NVID_CSRK, csrk)) return false;
    return true;
}

static uint32_t development_id_from_mac(const uint8_t mac[B_ADDR_LEN])
{
    return (uint32_t)mac[0] | ((uint32_t)mac[1] << 8) |
           ((uint32_t)mac[2] << 16) | ((uint32_t)mac[3] << 24);
}

void dpls_ble_identity_prepare(void)
{
    dpls_factory_identity_t factory;
    uint8_t mac[B_ADDR_LEN];
    uint8_t irk[KEYLEN];
    uint8_t csrk[KEYLEN];
    uint8_t addr_type;
    bool have_factory;

    memset(&factory, 0, sizeof(factory));
    have_factory = factory_identity_load(&factory);
    if (!select_identity_address(&factory, have_factory, mac, &addr_type)) return;
    if (!ensure_identity_keys(&factory, have_factory, irk, csrk)) return;

    memcpy(s_identity_mac, mac, B_ADDR_LEN);
    s_identity_addr_type = addr_type;
    s_device_id = have_factory ? factory.serial_number : development_id_from_mac(mac);
    s_factory_provisioned = have_factory;
    s_identity_mac_valid = true;
    (void)GAPRole_SetParameter(GAPROLE_IRK, KEYLEN, irk);
    (void)GAPRole_SetParameter(GAPROLE_SRK, KEYLEN, csrk);
}

void dpls_ble_identity_on_stack_started(void)
{
    uint8_t irk[KEYLEN];
    uint8_t hci_addr[B_ADDR_LEN];
    uint8_t zero_irk[KEYLEN];

    if (!s_identity_mac_valid) return;
    GAPRole_GetParameter(GAPROLE_IRK, irk);
    if (key_is_invalid(irk)) return;

    (void)GAP_ConfigDeviceAddr(s_identity_addr_type, s_identity_mac);

    GAPRole_GetParameter(GAPROLE_BD_ADDR, hci_addr);
    if (mac_is_invalid(hci_addr)) return;
    memset(zero_irk, 0, sizeof(zero_irk));
    (void)HCI_LE_AddDevToResolvingListCmd(s_identity_addr_type, hci_addr, zero_irk, irk);
}

void dpls_ble_identity_reset_bonding_keys(void)
{
    uint8_t erased[KEYLEN];
    memset(erased, 0xFF, sizeof(erased));
    /* This only clears SNV. Provisioned IRK/CSRK live in the immutable factory
     * sector and are restored on reboot, so factory reset cannot change identity. */
    (void)osal_snv_write(BLE_NVID_IRK, KEYLEN, erased);
    (void)osal_snv_write(BLE_NVID_CSRK, KEYLEN, erased);
}

uint32_t dpls_ble_identity_device_id(void)
{
    return s_identity_mac_valid ? s_device_id : 0u;
}

bool dpls_ble_identity_is_provisioned(void)
{
    return s_identity_mac_valid && s_factory_provisioned;
}
