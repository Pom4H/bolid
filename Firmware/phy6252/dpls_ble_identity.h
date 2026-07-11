#ifndef DPLS_BLE_IDENTITY_H
#define DPLS_BLE_IDENTITY_H

#include "bcomdef.h"

/* Must run before GAPRole_StartDevice(). */
void dpls_ble_identity_prepare(void);

/* Sync RPA with IRK after GAP_DeviceInit (GAPROLE_STARTED). */
void dpls_ble_identity_on_stack_started(void);

/* Erases persisted bonding keys; MAC is kept. Reboot after calling. */
void dpls_ble_identity_reset_bonding_keys(void);

#endif
