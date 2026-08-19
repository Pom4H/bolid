#ifndef DPLS_BLE_IDENTITY_H
#define DPLS_BLE_IDENTITY_H

#include "bcomdef.h"

/* Factory identity occupies the last 4 KiB sector of the 256 KiB application
 * flash window. The linker deliberately excludes this sector from firmware. */
#define DPLS_FACTORY_IDENTITY_FLASH_ADDR 0x1103F000u
#define DPLS_FACTORY_IDENTITY_RECORD_SIZE 64u

/* Must run before GAPRole_StartDevice(). */
void dpls_ble_identity_prepare(void);

/* Sync the selected public/static identity address after GAP_DeviceInit. */
void dpls_ble_identity_on_stack_started(void);

/* Clears runtime copies of BLE identity keys in SNV. Provisioned factory keys
 * are not touched and are restored from the immutable factory record on reboot. */
void dpls_ble_identity_reset_bonding_keys(void);

/* Stable 32-bit production serial number. On an unprovisioned development
 * board this temporarily falls back to a value derived from the chip MAC. */
uint32_t dpls_ble_identity_device_id(void);

/* TRUE when an address and identity keys were prepared successfully. */
bool dpls_ble_identity_is_ready(void);

/* TRUE only when a valid CRC-protected factory record was loaded. */
bool dpls_ble_identity_is_provisioned(void);

#endif
