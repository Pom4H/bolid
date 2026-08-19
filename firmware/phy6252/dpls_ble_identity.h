#ifndef DPLS_BLE_IDENTITY_H
#define DPLS_BLE_IDENTITY_H

#include "bcomdef.h"

/* Load/generate the stable identity and pairing keys. Safe to retry after the
 * BLE stack reaches GAPROLE_STARTED if the controller rejected an early HCI
 * address update. */
void dpls_ble_identity_prepare(void);

/* Retry/finalize controller identity after GAP_DeviceInit (GAPROLE_STARTED),
 * then synchronize the resolving list with the persistent IRK. */
void dpls_ble_identity_on_stack_started(void);

/* Advertising must stay disabled until the stable MAC and identity keys are
 * both installed. This prevents the PHY6252 default/invalid address escaping
 * into the air when controller setup fails transiently. */
bool dpls_ble_identity_ready(void);

/* Erases persisted bonding keys; MAC is kept. Reboot after calling. */
void dpls_ble_identity_reset_bonding_keys(void);

/* Stable 32-bit device id derived from the identity MAC (0 if not ready).
 * Reported in DEVICE_INFO_REPORT and BLE manufacturer data. */
uint32_t dpls_ble_identity_device_id(void);

#endif
