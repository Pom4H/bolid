#include "dpls_ble_identity.h"

#include "OSAL.h"
#include "flash.h"
#include "gap.h"
#include "hci.h"
#include "osal_snv.h"
#include "peripheral.h"
#include <string.h>

#define DPLS_BLE_MAC_SNV_ID 0x82u
#define DPLS_BLE_MAC_MAGIC 0x43414D44u /* "DMAC" */

typedef struct {
    uint32_t magic;
    uint8_t addr[B_ADDR_LEN];
} dpls_ble_mac_record_t;

/* SDK 3.1.2 определяет объект в flash.c, но не объявляет его в flash.h. */
extern chipMAddr_t g_chipMAddr;

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

static bool read_factory_mac(uint8_t out[B_ADDR_LEN])
{
    uint8_t i;

    check_chip_mAddr();
    if (g_chipMAddr.chipMAddrStatus != CHIP_ID_VALID) return false;

    for (i = 0; i < B_ADDR_LEN; ++i) {
        out[i] = g_chipMAddr.mAddr[B_ADDR_LEN - 1u - i];
    }
    return !mac_is_invalid(out);
}

static bool read_mac_snv(uint8_t out[B_ADDR_LEN])
{
    dpls_ble_mac_record_t record;
    if (osal_snv_read(DPLS_BLE_MAC_SNV_ID, sizeof(record), &record) != SUCCESS) return false;
    if (record.magic != DPLS_BLE_MAC_MAGIC || mac_is_invalid(record.addr)) return false;
    memcpy(out, record.addr, B_ADDR_LEN);
    return true;
}

static bool select_mac(uint8_t mac[B_ADDR_LEN])
{
    /* У PHY6252 уже есть заводской публичный адрес. Он является основным
     * источником истины и не требует генерации или записи flash при boot. */
    if (read_factory_mac(mac)) return true;

    /* Старый сохранённый адрес оставляем только как fallback для плат без
     * корректного factory word. Чтение безопасно до запуска BLE stack. */
    return read_mac_snv(mac);
}

static bool set_controller_public_addr(const uint8_t mac[B_ADDR_LEN])
{
    uint8_t controller_addr[B_ADDR_LEN];
    uint8_t i;

    for (i = 0; i < B_ADDR_LEN; ++i) {
        controller_addr[i] = mac[B_ADDR_LEN - 1u - i];
    }
    return HCI_EXT_SetBDADDRCmd(controller_addr) == HCI_SUCCESS;
}

void dpls_ble_identity_prepare(void)
{
    uint8_t mac[B_ADDR_LEN];

    /* До GAPRole_StartDevice только чтение. Никаких TRNG/HCI/flash-write:
     * этот участок выполняется на самом чувствительном раннем boot path. */
    s_identity_mac_valid = false;
    if (!select_mac(mac)) return;

    memcpy(s_identity_mac, mac, B_ADDR_LEN);
    s_identity_mac_valid = true;
}

void dpls_ble_identity_on_stack_started(void)
{
    uint8_t mac[B_ADDR_LEN];

    /* Контроллер настраиваем только после GAPROLE_STARTED — это порядок,
     * который уже был проверен на реальной PB-03F в рабочем RC3. */
    if (!s_identity_mac_valid) {
        if (!select_mac(mac)) return;
        memcpy(s_identity_mac, mac, B_ADDR_LEN);
        s_identity_mac_valid = true;
    }

    if (!set_controller_public_addr(s_identity_mac)) return;
    (void)GAP_ConfigDeviceAddr(ADDRTYPE_PUBLIC, NULL);

    /* Здесь намеренно нет osal_snv_write(). Первый advertising должен
     * произойти раньше любой необязательной записи flash. */
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
