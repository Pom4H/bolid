#ifndef DPLS_BLE_IDENTITY_H
#define DPLS_BLE_IDENTITY_H

#include "bcomdef.h"

/* Must run before GAPRole_StartDevice(). Returns false when the stable identity
 * or its IRK/CSRK cannot be loaded/generated/persisted; callers must not start
 * advertising with a partial or zero identity. */
bool dpls_ble_identity_prepare(void);

/* Sync RPA with IRK after GAP_DeviceInit (GAPROLE_STARTED). */
void dpls_ble_identity_on_stack_started(void);

/* Replace persisted IRK/CSRK with fresh random keys while keeping the stable MAC.
 * Returns false on RNG/NV/read-back failure so factory reset can fail closed
 * instead of rebooting with only part of its security state cleared. */
bool dpls_ble_identity_reset_bonding_keys(void);

/* Stable 32-bit device id derived from the identity MAC (0 if not ready).
 * Reported in DEVICE_INFO_REPORT and used by the app to key its name cache. */
uint32_t dpls_ble_identity_device_id(void);

#endif