#include "dpls_ble_identity.h"

#include "dpls_protocol.h"
#include "flash.h"
#include "gap.h"
#include "hci.h"
#include <stdbool.h>
#include <string.h>

#define DPLS_FACTORY_MAGIC 0x31444944u /* "DID1" */
#define DPLS_FACTORY_VERSION 1u
#define DPLS_FACTORY_OFF_MAGIC 0u
#define DPLS_FACTORY_OFF_VERSION 4u
#define DPLS_FACTORY_OFF_LENGTH 6u
#define DPLS_FACTORY_OFF_SERIAL 8u
#define DPLS_FACTORY_OFF_CRC 62u

#define DPLS_FLASH_UID_CMD 0x4bu
#define DPLS_FLASH_UID_SIZE 8u
#define DPLS_STATIC_ADDR_HEADER 0xc0u

/* SDK 3.1.2 определяет объект в flash.c, но не объявляет его в flash.h. */
extern chipMAddr_t g_chipMAddr;

static uint8_t identity_mac[B_ADDR_LEN];
static uint32_t device_id;
static bool identity_valid;
static bool identity_static;

static uint16_t rd16(const uint8_t *p)
{
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static uint32_t rd32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static bool bytes_invalid(const uint8_t *bytes, uint8_t length)
{
    uint8_t i;
    bool all_zero = true;
    bool all_ff = true;
    for (i = 0u; i < length; ++i) {
        all_zero = all_zero && bytes[i] == 0u;
        all_ff = all_ff && bytes[i] == 0xffu;
    }
    return all_zero || all_ff;
}

static bool mac_invalid(const uint8_t mac[B_ADDR_LEN])
{
    return bytes_invalid(mac, B_ADDR_LEN);
}

static bool read_chip_public_mac(uint8_t out[B_ADDR_LEN])
{
    uint8_t i;
    check_chip_mAddr();
    if (g_chipMAddr.chipMAddrStatus != CHIP_ID_VALID) return false;
    for (i = 0u; i < B_ADDR_LEN; ++i)
        out[i] = g_chipMAddr.mAddr[B_ADDR_LEN - 1u - i];
    return !mac_invalid(out);
}

/* The PHY6252 factory MAC/ChipID live in external flash around 0x11000900 and
 * are destroyed by a physical chip erase. The SPI flash Unique ID is OTP in the
 * flash die and survives that operation. Pre-series boards that already lost
 * the factory words therefore get a deterministic static-random BLE identity
 * from the UID instead of collapsing to device_id=0. Production boards keep
 * using their factory public address unchanged. */
static bool read_flash_unique_id(uint8_t out[DPLS_FLASH_UID_SIZE])
{
    memset(out, 0, DPLS_FLASH_UID_SIZE);
    spif_cmd(DPLS_FLASH_UID_CMD, 4u, DPLS_FLASH_UID_SIZE, 0u, 0u, 0u);
    spif_rddata(out, DPLS_FLASH_UID_SIZE);
    return !bytes_invalid(out, DPLS_FLASH_UID_SIZE);
}

static uint32_t uid_device_id(const uint8_t uid[DPLS_FLASH_UID_SIZE])
{
    uint32_t hash = 2166136261u;
    uint8_t i;
    for (i = 0u; i < DPLS_FLASH_UID_SIZE; ++i) {
        hash ^= uid[i];
        hash *= 16777619u;
    }
    if (hash == 0u || hash == 0xffffffffu) hash ^= 0x62520001u;
    return hash;
}

static bool build_recovery_identity(uint8_t out[B_ADDR_LEN], uint32_t *out_device_id)
{
    uint8_t uid[DPLS_FLASH_UID_SIZE];
    uint8_t i;
    if (!out_device_id || !read_flash_unique_id(uid)) return false;

    /* identity_mac is kept in human/display byte order. GAP uses the reversed
     * controller representation below, so setting the high two bits here makes
     * controller_addr[5] a valid Bluetooth static-random address header. */
    for (i = 0u; i < B_ADDR_LEN; ++i)
        out[i] = (uint8_t)(uid[i] ^ uid[(uint8_t)((i + 2u) % DPLS_FLASH_UID_SIZE)]);
    out[0] = (uint8_t)((out[0] & 0x3fu) | DPLS_STATIC_ADDR_HEADER);
    if (mac_invalid(out)) out[B_ADDR_LEN - 1u] ^= 0x01u;

    *out_device_id = uid_device_id(uid);
    memset(uid, 0, sizeof(uid));
    return true;
}

static uint32_t fallback_device_id(const uint8_t mac[B_ADDR_LEN])
{
    return (uint32_t)mac[0] |
           ((uint32_t)mac[1] << 8) |
           ((uint32_t)mac[2] << 16) |
           ((uint32_t)mac[3] << 24);
}

static bool read_factory_serial(uint32_t *serial)
{
    uint8_t raw[DPLS_FACTORY_IDENTITY_RECORD_SIZE];
    uint16_t stored_crc;
    uint32_t value;

    if (!serial) return false;
    if (hal_flash_read(DPLS_FACTORY_IDENTITY_FLASH_ADDR, raw, sizeof(raw)) != 0) return false;
    if (rd32(raw + DPLS_FACTORY_OFF_MAGIC) != DPLS_FACTORY_MAGIC) return false;
    if (rd16(raw + DPLS_FACTORY_OFF_VERSION) != DPLS_FACTORY_VERSION) return false;
    if (rd16(raw + DPLS_FACTORY_OFF_LENGTH) != DPLS_FACTORY_IDENTITY_RECORD_SIZE) return false;
    stored_crc = rd16(raw + DPLS_FACTORY_OFF_CRC);
    if (stored_crc != dpls_crc16(raw, DPLS_FACTORY_OFF_CRC)) return false;
    value = rd32(raw + DPLS_FACTORY_OFF_SERIAL);
    if (value == 0u || value == 0xffffffffu) return false;
    *serial = value;
    return true;
}

void dpls_ble_identity_prepare(void)
{
    uint32_t serial;
    uint32_t recovery_id = 0u;

    identity_static = false;
    identity_valid = read_chip_public_mac(identity_mac);
    if (identity_valid) {
        device_id = fallback_device_id(identity_mac);
    } else {
        identity_valid = build_recovery_identity(identity_mac, &recovery_id);
        identity_static = identity_valid;
        device_id = identity_valid ? recovery_id : 0u;
    }

    /* DID1 больше не владеет BLE address/keys и никогда не блокирует radio.
     * Если запись валидна, используем только production serial для device_id. */
    if (read_factory_serial(&serial)) device_id = serial;
}

void dpls_ble_identity_on_stack_started(void)
{
    uint8_t controller_addr[B_ADDR_LEN];
    uint8_t i;

    if (!identity_valid) dpls_ble_identity_prepare();
    if (!identity_valid) return;

    for (i = 0u; i < B_ADDR_LEN; ++i)
        controller_addr[i] = identity_mac[B_ADDR_LEN - 1u - i];

    if (identity_static) {
        (void)GAP_ConfigDeviceAddr(ADDRTYPE_STATIC, controller_addr);
        return;
    }

    /* Controller/HCI жив только после GAPROLE_STARTED. Ошибка здесь не должна
     * запрещать advertising: silicon public address всё равно остаётся fallback. */
    if (HCI_EXT_SetBDADDRCmd(controller_addr) == HCI_SUCCESS)
        (void)GAP_ConfigDeviceAddr(ADDRTYPE_PUBLIC, NULL);
}

uint32_t dpls_ble_identity_device_id(void)
{
    return device_id;
}
