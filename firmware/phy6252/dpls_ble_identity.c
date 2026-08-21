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

/* SDK 3.1.2 определяет объект в flash.c, но не объявляет его в flash.h. */
extern chipMAddr_t g_chipMAddr;

static uint8_t identity_mac[B_ADDR_LEN];
static uint32_t device_id;
static bool identity_valid;

static uint16_t rd16(const uint8_t *p)
{
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static uint32_t rd32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static bool mac_invalid(const uint8_t mac[B_ADDR_LEN])
{
    uint8_t i;
    bool all_zero = true;
    bool all_ff = true;
    for (i = 0u; i < B_ADDR_LEN; ++i) {
        all_zero = all_zero && mac[i] == 0u;
        all_ff = all_ff && mac[i] == 0xffu;
    }
    return all_zero || all_ff;
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

    identity_valid = read_chip_public_mac(identity_mac);
    device_id = identity_valid ? fallback_device_id(identity_mac) : 0u;

    /* DID1 больше не владеет BLE address/keys и никогда не блокирует radio.
     * Если запись валидна, используем только production serial для device_id. */
    if (read_factory_serial(&serial)) device_id = serial;
}

void dpls_ble_identity_on_stack_started(void)
{
    uint8_t controller_addr[B_ADDR_LEN];
    uint8_t i;

    if (!identity_valid) identity_valid = read_chip_public_mac(identity_mac);
    if (!identity_valid) return;

    for (i = 0u; i < B_ADDR_LEN; ++i)
        controller_addr[i] = identity_mac[B_ADDR_LEN - 1u - i];

    /* Controller/HCI жив только после GAPROLE_STARTED. Ошибка здесь не должна
     * запрещать advertising: silicon public address всё равно остаётся fallback. */
    if (HCI_EXT_SetBDADDRCmd(controller_addr) == HCI_SUCCESS)
        (void)GAP_ConfigDeviceAddr(ADDRTYPE_PUBLIC, NULL);
}

uint32_t dpls_ble_identity_device_id(void)
{
    return device_id;
}
