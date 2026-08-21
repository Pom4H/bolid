#ifndef DPLS_BLE_IDENTITY_H
#define DPLS_BLE_IDENTITY_H

#include "bcomdef.h"

/* Factory identity occupies the last 4 KiB sector of the 256 KiB application
 * flash window. The linker deliberately excludes this sector from firmware. */
#define DPLS_FACTORY_IDENTITY_FLASH_ADDR 0x1103F000u
#define DPLS_FACTORY_IDENTITY_RECORD_SIZE 64u

/* Must run before GAPRole_StartDevice(). A valid factory record is preferred.
 * Boards upgraded from 1.3.x may fall back to their existing chip/public MAC
 * and SNV identity keys so a normal firmware flash cannot make them disappear. */
void dpls_ble_identity_prepare(void);

/* Sync the selected public/static identity address after GAP_DeviceInit. */
void dpls_ble_identity_on_stack_started(void);

/* Clears runtime copies of BLE identity keys in SNV. Factory keys are restored
 * from the factory record; legacy boards generate replacement keys on reboot. */
void dpls_ble_identity_reset_bonding_keys(void);

/* Stable 32-bit production serial from the factory record, or the 1.3.x
 * MAC-derived NodeId on a legacy/unprovisioned development board. */
uint32_t dpls_ble_identity_device_id(void);

/* TRUE when either factory or compatible legacy identity is applied. */
bool dpls_ble_identity_is_ready(void);

/* TRUE only when a valid CRC-protected factory record was loaded. */
bool dpls_ble_identity_is_provisioned(void);

#endif
