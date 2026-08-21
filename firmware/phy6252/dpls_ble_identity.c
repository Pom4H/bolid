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

#define DPLS_FACTORY_MAGIC 0x31444944u /* bytes: "DID1" */
#define DPLS_FACTORY_VERSION 1u
#define DPLS_FACTORY_FLAG_BLE_STATIC 0x0001u
#define DPLS_FACTORY_FLAG_IRK 0x0002u
#define DPLS_FACTORY_FLAG_CSRK 0x0004u
#define DPLS_FACTORY_FLAGS_KNOWN (DPLS_FACTORY_FLAG_BLE_STATIC | DPLS_FACTORY_FLAG_IRK | DPLS_FACTORY_FLAG_CSRK)
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

/* 1.3.x stored a public-style fallback MAC here when the chip address was not
 * usable. Keep this read-only migration path so an already deployed board can
 * boot a new image without first receiving a new factory sector. */
#define DPLS_LEGACY_BLE_MAC_SNV_ID 0x82u
#define DPLS_LEGACY_BLE_MAC_MAGIC 0x43414D44u /* "DMAC" */

typedef struct {
    uint32_t serial_number;
    uint16_t hardware_revision;
    uint16_t flags;
    uint8_t ble_addr[B_ADDR_LEN];
    uint8_t ble_addr_type;
    uint8_t irk[KEYLEN];
    uint8_t csrk[KEYLEN];
} dpls_factory_identity_t;

typedef struct {
    uint32_t magic;
    uint8_t addr[B_ADDR_LEN];
} dpls_legacy_ble_mac_record_t;

/* PHY62XX SDK 3.1.2 defines this object in flash.c but omits the extern from
 * flash.h. Use the vendor decoder/state instead of depending on its raw format. */
extern chipMAddr_t g_chipMAddr;

static uint8_t s_identity_mac[B_ADDR_LEN];
static uint8_t s_identity_addr_type = ADDRTYPE_PUBLIC;
static uint32_t s_device_id;
static bool s_identity_mac_valid;
static bool s_identity_ready;
static bool s_factory_provisioned;

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
    if ((out->flags & (uint16_t)~DPLS_FACTORY_FLAGS_KNOWN) != 0u) return false;
    if ((out->flags & (DPLS_FACTORY_FLAG_IRK | DPLS_FACTORY_FLAG_CSRK)) !=
        (DPLS_FACTORY_FLAG_IRK | DPLS_FACTORY_FLAG_CSRK)) return false;

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

    if (key_is_invalid(out->irk) || key_is_invalid(out->csrk)) return false;
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

static bool read_legacy_mac_snv(uint8_t out[B_ADDR_LEN])
{
    dpls_legacy_ble_mac_record_t record;
    if (osal_snv_read(DPLS_LEGACY_BLE_MAC_SNV_ID, sizeof(record), &record) != SUCCESS) return false;
    if (record.magic != DPLS_LEGACY_BLE_MAC_MAGIC || mac_is_invalid(record.addr)) return false;
    /* 1.3.x generated a public-style address by clearing the two high bits. */
    if ((record.addr[0] & 0xC0u) == 0xC0u) return false;
    memcpy(out, record.addr, B_ADDR_LEN);
    return true;
}

static bool random_bytes(uint8_t *out, uint8_t length)
{
    return LL_ENC_GenerateTrueRandNum(out, length) == SUCCESS;
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

static bool ensure_legacy_identity_keys(uint8_t irk[KEYLEN], uint8_t csrk[KEYLEN])
{
    if (read_key_snv(BLE_NVID_IRK, irk) && read_key_snv(BLE_NVID_CSRK, csrk)) return true;
    if (!random_bytes(irk, KEYLEN) || !random_bytes(csrk, KEYLEN)) return false;
    if (!write_key_snv(BLE_NVID_IRK, irk) || !write_key_snv(BLE_NVID_CSRK, csrk)) return false;
    return true;
}

static uint32_t legacy_device_id_from_mac(const uint8_t mac[B_ADDR_LEN])
{
    return (uint32_t)mac[0] | ((uint32_t)mac[1] << 8) |
           ((uint32_t)mac[2] << 16) | ((uint32_t)mac[3] << 24);
}

static bool legacy_identity_load(dpls_factory_identity_t *out,
                                 uint8_t mac[B_ADDR_LEN],
                                 uint8_t *addr_type)
{
    uint32_t device_id;

    /* Preserve the 1.3.x identity contract for development/upgrade boards.
     * Prefer the immutable chip public address; use the old SNV fallback only
     * when that chip address is unavailable. Never generate a new MAC here. */
    if (!read_chip_factory_mac(mac) && !read_legacy_mac_snv(mac)) return false;
    device_id = legacy_device_id_from_mac(mac);
    if (device_id == 0u || device_id == 0xffffffffu) return false;
    if (!ensure_legacy_identity_keys(out->irk, out->csrk)) return false;

    out->serial_number = device_id;
    out->hardware_revision = 0u;
    out->flags = DPLS_FACTORY_FLAG_IRK | DPLS_FACTORY_FLAG_CSRK;
    memcpy(out->ble_addr, mac, B_ADDR_LEN);
    out->ble_addr_type = DPLS_FACTORY_BLE_ADDR_CHIP_PUBLIC;
    *addr_type = ADDRTYPE_PUBLIC;
    return true;
}

static void display_to_controller_addr(const uint8_t display[B_ADDR_LEN],
                                       uint8_t controller[B_ADDR_LEN])
{
    uint8_t i;
    for (i = 0; i < B_ADDR_LEN; ++i) {
        controller[i] = display[B_ADDR_LEN - 1u - i];
    }
}

static bool set_controller_public_addr(const uint8_t mac[B_ADDR_LEN])
{
    uint8_t controller_addr[B_ADDR_LEN];
    display_to_controller_addr(mac, controller_addr);
    if (HCI_EXT_SetBDADDRCmd(controller_addr) != HCI_SUCCESS) return false;
    (void)HCI_ReadBDADDRCmd();
    return true;
}

static bool configure_static_identity_addr(const uint8_t mac[B_ADDR_LEN])
{
    uint8_t controller_addr[B_ADDR_LEN];

    /* GAP_DeviceInit snapshots the local address used by the peripheral role.
     * Static-random identity therefore has to be configured before
     * GAPRole_StartDevice(), not after GAPROLE_STARTED. */
    display_to_controller_addr(mac, controller_addr);
    return GAP_ConfigDeviceAddr(ADDRTYPE_STATIC, controller_addr) == SUCCESS;
}

static bool select_identity_address(const dpls_factory_identity_t *factory,
                                    uint8_t mac[B_ADDR_LEN],
                                    uint8_t *addr_type)
{
    if ((factory->flags & DPLS_FACTORY_FLAG_BLE_STATIC) != 0u) {
        memcpy(mac, factory->ble_addr, B_ADDR_LEN);
        *addr_type = ADDRTYPE_STATIC;
        return true;
    }

    if (!read_chip_factory_mac(mac)) return false;
    *addr_type = ADDRTYPE_PUBLIC;
    return true;
}

void dpls_ble_identity_prepare(void)
{
    dpls_factory_identity_t identity;
    uint8_t mac[B_ADDR_LEN];
    uint8_t addr_type;
    bool factory_loaded;

    s_identity_ready = false;
    s_identity_mac_valid = false;
    s_factory_provisioned = false;
    s_device_id = 0u;

    memset(&identity, 0, sizeof(identity));
    factory_loaded = factory_identity_load(&identity);
    if (factory_loaded) {
        if (!select_identity_address(&identity, mac, &addr_type)) return;
    } else if (!legacy_identity_load(&identity, mac, &addr_type)) {
        return;
    }

    /* Static-random address selection is a GAP initialization input. The PHY6252
     * role captures its local address during GAP_DeviceInit(), so configure it
     * here while SimpleBLEPeripheral_Init() is still before GAPRole_StartDevice(). */
    if (addr_type == ADDRTYPE_STATIC && !configure_static_identity_addr(mac)) return;

    memcpy(s_identity_mac, mac, B_ADDR_LEN);
    s_identity_addr_type = addr_type;
    s_device_id = identity.serial_number;
    s_factory_provisioned = factory_loaded;
    s_identity_mac_valid = true;
    (void)GAPRole_SetParameter(GAPROLE_IRK, KEYLEN, identity.irk);
    (void)GAPRole_SetParameter(GAPROLE_SRK, KEYLEN, identity.csrk);
}

void dpls_ble_identity_on_stack_started(void)
{
    uint8_t irk[KEYLEN];
    uint8_t hci_addr[B_ADDR_LEN];
    uint8_t zero_irk[KEYLEN];

    s_identity_ready = false;
    if (!s_identity_mac_valid) dpls_ble_identity_prepare();
    if (!s_identity_mac_valid) return;

    /* Public identity still needs the controller to be live: PHY6252 may reject
     * HCI_EXT_SetBDADDRCmd before GAPROLE_STARTED. Static identity was already
     * configured before GAP_DeviceInit and must not be changed here. */
    if (s_identity_addr_type == ADDRTYPE_PUBLIC) {
        if (!set_controller_public_addr(s_identity_mac)) return;
        if (GAP_ConfigDeviceAddr(ADDRTYPE_PUBLIC, NULL) != SUCCESS) return;
    }

    GAPRole_GetParameter(GAPROLE_IRK, irk);
    if (key_is_invalid(irk)) return;

    GAPRole_GetParameter(GAPROLE_BD_ADDR, hci_addr);
    if (mac_is_invalid(hci_addr)) return;

    memset(zero_irk, 0, sizeof(zero_irk));
    (void)HCI_LE_AddDevToResolvingListCmd(s_identity_addr_type, hci_addr, zero_irk, irk);
    s_identity_ready = true;
}

void dpls_ble_identity_reset_bonding_keys(void)
{
    uint8_t erased[KEYLEN];
    memset(erased, 0xFF, sizeof(erased));
    /* Clear stack runtime/SNV copies. Factory records restore their keys on the
     * next boot; legacy boards generate replacements if no valid SNV keys remain. */
    (void)osal_snv_write(BLE_NVID_IRK, KEYLEN, erased);
    (void)osal_snv_write(BLE_NVID_CSRK, KEYLEN, erased);
}

uint32_t dpls_ble_identity_device_id(void)
{
    return s_identity_mac_valid ? s_device_id : 0u;
}

bool dpls_ble_identity_is_ready(void)
{
    return s_identity_ready && s_identity_mac_valid;
}

bool dpls_ble_identity_is_provisioned(void)
{
    return s_identity_mac_valid && s_factory_provisioned;
}
