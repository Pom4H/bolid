#include "dpls_phy6252_storage.h"

#include "dpls_phy6252_supervisor.h"
#include "bcomdef.h"
#include "osal_snv.h"
#include <string.h>

void dpls_phy6252_storage_reset_ble_bonding_keys(void)
{
    uint8_t erased[KEYLEN];
    memset(erased, 0xFF, sizeof(erased));

    /* Clear only the BLE stack's runtime/SNV copies. Factory IRK/CSRK remain in
     * the protected identity sector and dpls_ble_identity_prepare() restores
     * them into GAPRole on the next boot. */
    dpls_phy6252_supervisor_blocking_io_begin();
    (void)osal_snv_write(BLE_NVID_IRK, KEYLEN, erased);
    (void)osal_snv_write(BLE_NVID_CSRK, KEYLEN, erased);
    dpls_phy6252_supervisor_blocking_io_end();
}
